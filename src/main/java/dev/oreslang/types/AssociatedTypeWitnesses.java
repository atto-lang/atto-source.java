package dev.oreslang.types;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compile-time-only associated-type equations attached to coherent witnesses.
 *
 * <p>This is a frontend/checker primitive. Source syntax, imported module
 * linking, HIR/MIR projection lowering and guest Native Image code generation
 * are independent admission gates; this is not a runtime typeclass registry.
 * Only monomorphic, fully ground projections can enter strict AOT lowering.
 */
public final class AssociatedTypeWitnesses {
    private static final int MAX_MEMBERS = 64;
    private static final int MAX_WITNESSES = 4096;
    private static final int MAX_TYPE_NODES = 1024;

    public record Resolved(TypeclassRegistry.Instance witness,
                           Map<String, TypeclassRegistry.TypeRef> types) {
        public Resolved {
            Objects.requireNonNull(witness, "witness");
            types = Collections.unmodifiableMap(new TreeMap<>(types));
            if (types.values().stream().anyMatch(value -> !value.isGround())) {
                throw new IllegalArgumentException("unresolved associated type projection");
            }
        }
        public TypeclassRegistry.TypeRef type(String member) {
            TypeclassRegistry.TypeRef value = types.get(member);
            if (value == null) {
                throw new IllegalArgumentException("unknown associated type: " + member);
            }
            return value;
        }
    }

    private final TypeclassRegistry.Registry coherence = new TypeclassRegistry.Registry();
    private final Map<TypeclassRegistry.Typeclass, Set<String>> declarations = new LinkedHashMap<>();
    private final Map<TypeclassRegistry.Instance, Map<String, TypeclassRegistry.TypeRef>> equations
            = new LinkedHashMap<>();
    private final Set<String> dictionarySymbols = new HashSet<>();

    /**
     * Declare all associated members before registering witnesses. No
     * post-registration extension of a trait's equation set is permitted.
     */
    public void define(TypeclassRegistry.Typeclass protocol, Set<String> members) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(members, "members");
        if (members.isEmpty() || members.size() > MAX_MEMBERS
                || members.stream().anyMatch(name -> name == null
                    || !name.matches("[a-z][a-z0-9_]*"))) {
            throw new IllegalArgumentException("associated members require bounded snake_case names");
        }
        coherence.define(protocol);
        declarations.put(protocol, Set.copyOf(members));
    }

    /**
     * All equations must be present and may mention only variables bound
     * in the witness head. Checks precede mutation of the coherent registry.
     */
    public void register(TypeclassRegistry.Instance witness,
                         Map<String, TypeclassRegistry.TypeRef> associated) {
        Objects.requireNonNull(witness, "witness");
        Objects.requireNonNull(associated, "associated");
        Set<String> members = declarations.get(witness.typeclass());
        if (members == null || !members.equals(associated.keySet())) {
            throw new IllegalArgumentException("missing, unknown or incomplete associated equations");
        }
        if (equations.size() >= MAX_WITNESSES
                || dictionarySymbols.contains(witness.dictionarySymbol())) {
            throw new IllegalArgumentException("associated witness capacity or symbol collision");
        }
        Set<String> bound = new HashSet<>();
        int[] budget = {MAX_TYPE_NODES};
        for (var argument : witness.arguments()) {
            collectVariables(argument, bound, budget);
        }
        for (var value : associated.values()) {
            Objects.requireNonNull(value, "associated type equation");
            requireBoundVariables(value, bound, budget);
        }
        // Existing coherence logic checks orphans, all potential overlaps,
        // arity and nominal heads. Publish equations only after it succeeds.
        coherence.register(witness);
        dictionarySymbols.add(witness.dictionarySymbol());
        equations.put(witness, Collections.unmodifiableMap(new TreeMap<>(associated)));
    }

    /** Resolve once during typed compilation; no dynamic witness search. */
    public Resolved resolve(TypeclassRegistry.Typeclass protocol,
                            List<TypeclassRegistry.TypeRef> groundArguments) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(groundArguments, "groundArguments");
        int[] incomingBudget = {MAX_TYPE_NODES};
        for (var argument : groundArguments) {
            Objects.requireNonNull(argument, "ground witness argument");
            // Resolving a deeply recursive user type before admission is not safe.
            requireBoundVariables(argument, Set.of(), incomingBudget);
        }
        TypeclassRegistry.Registry.Resolution evidence =
                coherence.resolveWithBindings(protocol, groundArguments);
        Map<String, TypeclassRegistry.TypeRef> equationsForWitness =
                equations.get(evidence.instance());
        if (equationsForWitness == null) {
            throw new IllegalStateException("unregistered associated equations for witness");
        }
        Map<String, TypeclassRegistry.TypeRef> resolved = new TreeMap<>();
        int[] budget = {MAX_TYPE_NODES};
        for (var entry : equationsForWitness.entrySet()) {
            resolved.put(entry.getKey(), substitute(entry.getValue(), evidence.bindings(), budget));
        }
        return new Resolved(evidence.instance(), resolved);
    }

    private static void spend(int[] budget) {
        if (--budget[0] < 0) {
            throw new IllegalArgumentException("associated type graph exceeds static proof budget");
        }
    }

    private static void collectVariables(TypeclassRegistry.TypeRef ref,
                                         Set<String> found, int[] budget) {
        spend(budget);
        if (ref.variable()) {
            found.add(ref.name());
        } else {
            for (var child : ref.arguments()) collectVariables(child, found, budget);
        }
    }

    private static void requireBoundVariables(TypeclassRegistry.TypeRef ref,
                                              Set<String> bound, int[] budget) {
        spend(budget);
        if (ref.variable()) {
            if (!bound.contains(ref.name())) {
                throw new IllegalArgumentException(
                        "unbound variable in associated type: " + ref.name());
            }
        } else {
            for (var child : ref.arguments()) requireBoundVariables(child, bound, budget);
        }
    }

    private static TypeclassRegistry.TypeRef substitute(
            TypeclassRegistry.TypeRef ref,
            Map<String, TypeclassRegistry.TypeRef> bindings, int[] budget) {
        spend(budget);
        if (ref.variable()) {
            TypeclassRegistry.TypeRef resolved = bindings.get(ref.name());
            if (resolved == null || !resolved.isGround()) {
                throw new IllegalArgumentException("unresolved associated variable: " + ref.name());
            }
            return resolved;
        }
        var args = ref.arguments().stream()
                .map(arg -> substitute(arg, bindings, budget))
                .toArray(TypeclassRegistry.TypeRef[]::new);
        return TypeclassRegistry.TypeRef.named(ref.module(), ref.name(), args);
    }
}
