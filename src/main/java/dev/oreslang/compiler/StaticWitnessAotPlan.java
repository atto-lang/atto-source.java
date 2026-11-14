package dev.oreslang.compiler;

import dev.oreslang.types.TypeclassRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Compile-time-only, bounded STATIC_WITNESS selection for fully ground
 * typeclass applications. This is a planner, NOT an emitted Native Image
 * function, HIR/MIR rewrite, or runtime dictionary registry.
 *
 * <p>A subsequent typed lowering pass must prove that each named target is
 * actually emitted, revalidate ownership/effects/capabilities after macros,
 * and reject any unresolved specialization before guest artifact emission.
 */
public final class StaticWitnessAotPlan {
    private static final int MAX_SPECIALIZATIONS = 4096;
    private static final int MAX_TYPE_NODES = 1024;
    private static final int MAX_TYPE_DEPTH = 48;

    private StaticWitnessAotPlan() { }

    public record Entry(
            String callable,
            TypeclassRegistry.Typeclass typeclass,
            List<TypeclassRegistry.TypeRef> concreteArguments,
            String dictionarySymbol,
            String targetSymbol,
            Map<String, TypeclassRegistry.TypeRef> substitutions) {
        public Entry {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(typeclass);
            concreteArguments = List.copyOf(concreteArguments);
            Objects.requireNonNull(dictionarySymbol);
            Objects.requireNonNull(targetSymbol);
            substitutions = Collections.unmodifiableMap(new TreeMap<>(substitutions));
        }
    }

    /**
     * No mutable runtime lookup. The builder is compiler-owned and discarded
     * before producing an AOT image. It never compiles source or executes code.
     */
    public static final class Builder {
        private final Map<String, Entry> specializations = new TreeMap<>();
        private final Map<String, TypeclassRegistry.Instance> dictionaryOwners =
                new LinkedHashMap<>();
        private final Map<String, String> targetOrigins = new LinkedHashMap<>();

        public Entry add(
                TypeclassRegistry.Registry checkedRegistry,
                TypeclassRegistry.Typeclass typeclass,
                List<TypeclassRegistry.TypeRef> groundArguments,
                String callable) {
            Objects.requireNonNull(checkedRegistry, "coherent witness registry");
            Objects.requireNonNull(typeclass, "typeclass");
            Objects.requireNonNull(groundArguments, "concrete type arguments");
            requireSymbol(callable, "callable symbol");
            if (groundArguments.size() != typeclass.arity())
                throw new IllegalArgumentException("static witness arity mismatch");

            int[] remaining = {MAX_TYPE_NODES};
            for (var argument : groundArguments) {
                Objects.requireNonNull(argument, "type argument");
                validateConcrete(argument, remaining);
            }
            // The existing typeclass coherence engine performs unique static
            // matching and deterministic substitutions, never a runtime search.
            var resolution = checkedRegistry.resolveWithBindings(typeclass, groundArguments);
            var instance = resolution.instance();
            requireSymbol(instance.dictionarySymbol(), "dictionary symbol");
            var prior = dictionaryOwners.get(instance.dictionarySymbol());
            if (prior != null && !prior.equals(instance)) {
                throw new IllegalArgumentException(
                        "distinct static witnesses reuse one dictionary ABI symbol");
            }

            var key = canonicalKey(callable, typeclass, groundArguments,
                                   instance.dictionarySymbol());
            Entry existing = specializations.get(key);
            if (existing != null) return existing;
            if (specializations.size() >= MAX_SPECIALIZATIONS)
                throw new IllegalArgumentException("AOT static witness specialization budget exceeded");

            String target = callable + "$witness$" + sha256(key);
            String priorOrigin = targetOrigins.get(target);
            if (priorOrigin != null && !priorOrigin.equals(key))
                throw new IllegalArgumentException("static witness target symbol collision");
            Entry selected = new Entry(
                    callable, typeclass, groundArguments,
                    instance.dictionarySymbol(), target, resolution.bindings());
            // All fallible validation precedes mutation. Rejected input must
            // leave the compiler's reachable specialization plan unchanged.
            dictionaryOwners.putIfAbsent(instance.dictionarySymbol(), instance);
            targetOrigins.putIfAbsent(target, key);
            specializations.put(key, selected);
            return selected;
        }

        /** Immutable deterministic snapshot for an independently checked codegen pass. */
        public List<Entry> close() {
            return List.copyOf(specializations.values());
        }
    }

    private static void requireSymbol(String symbol, String name) {
        if (symbol == null || symbol.length() > 256 ||
                !symbol.matches("[A-Za-z_$][A-Za-z0-9_.$]*")) {
            throw new IllegalArgumentException(name + " must have canonical static identity");
        }
    }

    private record Visit(TypeclassRegistry.TypeRef type, int depth) { }

    private static void validateConcrete(TypeclassRegistry.TypeRef root, int[] budget) {
        var queue = new ArrayDeque<Visit>();
        queue.add(new Visit(root, 0));
        while (!queue.isEmpty()) {
            var visit = queue.removeLast();
            var type = visit.type();
            if (--budget[0] < 0 || visit.depth() > MAX_TYPE_DEPTH)
                throw new IllegalArgumentException("AOT witness type graph exceeds proof budget");
            if (type.variable())
                throw new IllegalArgumentException("AOT static witness needs fully ground type arguments");
            if (type.module().length() > 256 || type.name().length() > 256)
                throw new IllegalArgumentException("AOT static witness type identity exceeds bound");
            for (var child : type.arguments()) {
                queue.add(new Visit(Objects.requireNonNull(child), visit.depth() + 1));
            }
        }
    }

    private static String canonicalKey(
            String callable, TypeclassRegistry.Typeclass typeclass,
            List<TypeclassRegistry.TypeRef> arguments, String dictionary) {
        var out = new StringBuilder();
        word(out, callable);
        word(out, typeclass.module());
        word(out, typeclass.name());
        out.append(typeclass.arity()).append(':');
        word(out, dictionary);
        out.append(arguments.size()).append(':');
        for (var arg : arguments) appendType(out, arg);
        return out.toString();
    }

    // Prefix lengths make namespace/name boundaries injective, including
    // deliberately adversarial ':' and '.' characters in nominal identifiers.
    private static void word(StringBuilder out, String value) {
        out.append(value.length()).append(':').append(value);
    }

    private static void appendType(StringBuilder out, TypeclassRegistry.TypeRef type) {
        word(out, type.module());
        word(out, type.name());
        out.append(type.arguments().size()).append(':');
        for (var child : type.arguments()) appendType(out, child);
    }

    private static String sha256(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("required compiler SHA-256 provider missing", impossible);
        }
    }
}
