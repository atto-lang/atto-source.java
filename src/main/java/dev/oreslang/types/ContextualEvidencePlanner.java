package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compile-time elaboration of contextual typeclass requirements into static
 * dictionary symbols and concrete generic substitutions.
 *
 * <p>This is compiler-only metadata: it does not perform a runtime lookup,
 * construct a guest object, synthesize a type conversion, or confer actor /
 * ownership / effect capabilities. Source-level given/using parsing and
 * backend call lowering are separate admission gates.
 */
public final class ContextualEvidencePlanner {
    private static final int MAX_SLOTS = 128;
    private static final int MAX_TYPE_NODES = 4096;

    private ContextualEvidencePlanner() { }

    /** One compiler-resolved contextual parameter in declaration order. */
    public record Requirement(String slot, TypeclassRegistry.Typeclass typeclass,
                              List<TypeclassRegistry.TypeRef> arguments) {
        public Requirement {
            if (slot == null || slot.isBlank() || slot.startsWith("$")) {
                throw new IllegalArgumentException("invalid contextual evidence slot");
            }
            Objects.requireNonNull(typeclass, "typeclass");
            arguments = List.copyOf(arguments);
        }
    }

    /** One statically selected dictionary, including template substitutions. */
    public record Binding(String slot, TypeclassRegistry.Typeclass typeclass,
                          List<TypeclassRegistry.TypeRef> concreteArguments,
                          String dictionarySymbol,
                          Map<String, TypeclassRegistry.TypeRef> substitutions,
                          boolean explicit) {
        public Binding {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(typeclass, "typeclass");
            concreteArguments = List.copyOf(concreteArguments);
            if (dictionarySymbol == null || dictionarySymbol.isBlank()) {
                throw new IllegalArgumentException("dictionary symbol cannot be empty");
            }
            substitutions = Map.copyOf(substitutions);
        }
    }

    /** Ordered, immutable AOT call-site evidence metadata. */
    public record Plan(List<Binding> bindings) {
        public Plan { bindings = List.copyOf(bindings); }

        public List<String> dictionarySymbols() {
            return bindings.stream().map(Binding::dictionarySymbol).toList();
        }
    }

    /**
     * Elaborate evidence after generic inference/default resolution, never
     * before. All registered/imported instances must already be loaded into
     * the one coherent registry; import order cannot establish precedence.
     *
     * <p>An explicit witness is a reference to a registered dictionary symbol
     * for the exact concrete goal, not user-supplied runtime evidence. The
     * explicit path cannot bypass coherence, orphan checks, or type equality.
     * In the absence of explicit evidence the unique registered witness is
     * selected; missing witnesses are compile errors, not conversions.
     */
    public static Plan plan(TypeclassRegistry.Registry registry,
                            List<Requirement> requirements,
                            Map<String, String> explicitSymbols) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(explicitSymbols, "explicitSymbols");
        if (requirements.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("too many contextual evidence slots");
        }

        Set<String> slots = new HashSet<>();
        int[] remainingNodes = {MAX_TYPE_NODES};
        List<Binding> staged = new ArrayList<>(requirements.size());
        for (Requirement required : requirements) {
            Objects.requireNonNull(required, "contextual requirement");
            if (!slots.add(required.slot())) {
                throw new IllegalArgumentException(
                        "duplicate contextual evidence slot: " + required.slot());
            }
            if (required.arguments().size() != required.typeclass().arity()) {
                throw new IllegalArgumentException(
                        "contextual evidence arity mismatch: " + required.slot());
            }
            for (TypeclassRegistry.TypeRef type : required.arguments()) {
                requireGroundBounded(type, remainingNodes);
            }

            TypeclassRegistry.Registry.Resolution resolution =
                    registry.resolveWithBindings(required.typeclass(), required.arguments());
            String symbol = resolution.instance().dictionarySymbol();
            boolean explicit = explicitSymbols.containsKey(required.slot());
            if (explicit && !symbol.equals(explicitSymbols.get(required.slot()))) {
                throw new IllegalArgumentException(
                        "explicit evidence does not match coherent instance for slot: "
                                + required.slot());
            }
            staged.add(new Binding(required.slot(), required.typeclass(),
                    required.arguments(), symbol, resolution.bindings(), explicit));
        }

        Set<String> unknown = new TreeSet<>(explicitSymbols.keySet());
        unknown.removeAll(slots);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("explicit evidence for unknown slot(s): " + unknown);
        }
        return new Plan(staged);
    }

    private static void requireGroundBounded(TypeclassRegistry.TypeRef root, int[] remaining) {
        ArrayDeque<TypeclassRegistry.TypeRef> pending = new ArrayDeque<>();
        pending.add(Objects.requireNonNull(root, "concrete type"));
        while (!pending.isEmpty()) {
            if (--remaining[0] < 0) {
                throw new IllegalArgumentException("contextual evidence type graph exceeds static budget");
            }
            TypeclassRegistry.TypeRef type = pending.removeLast();
            if (type.variable()) {
                throw new IllegalArgumentException(
                        "contextual evidence requires concrete types after generic inference");
            }
            pending.addAll(type.arguments());
        }
    }
}
