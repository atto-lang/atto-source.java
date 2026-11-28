package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Staged compile-time bundle refinement over already resolved formal identities.
 * Does not parse syntax, evaluate constants, infer types, or admit guest code. */
final class ParametricBundleRefinement {
    private ParametricBundleRefinement() {}

    record Formal<V>(String name, Predicate<V> accepts) {
        Formal { Objects.requireNonNull(name); Objects.requireNonNull(accepts); }
    }
    record Argument<V>(String name, V value) {
        Argument { Objects.requireNonNull(name); Objects.requireNonNull(value); }
    }

    /** Only an explicit refinement may replace a seed binding. The result is
     * ordered by the schema and immutable; the input lists remain untouched.
     * A caller must still resolve defaults, inference holes and cross-slot constraints. */
    static <V> Map<String, V> refine(List<Formal<V>> schema,
            List<Argument<V>> seed, List<Argument<V>> replacements) {
        return bind(schema, seed, replacements, true);
    }

    /** Ordinary bundle application never replaces an already bound formal. */
    static <V> Map<String, V> apply(List<Formal<V>> schema,
            List<Argument<V>> first, List<Argument<V>> additional) {
        return bind(schema, first, additional, false);
    }

    /** Resolve explicit inherited-slot holes from already checked unique evidence.
     * No default fallback, implicit seed replacement, or partial publication. */
    static <V> Map<String, V> refineInferred(List<Formal<V>> schema,
            List<Argument<V>> seed, List<Argument<V>> replacements,
            List<String> holes, List<Argument<V>> evidence) {
        Map<String, V> base = apply(schema, seed, List.of());
        Map<String, V> explicit = apply(schema, replacements, List.of());
        Map<String, V> observed = apply(schema, evidence, List.of());
        java.util.Set<String> requested = new java.util.HashSet<>();
        for (String hole : holes) {
            if (!requested.add(hole) || !base.containsKey(hole)
                    || explicit.containsKey(hole)) {
                throw new IllegalArgumentException("invalid inherited inference hole: " + hole);
            }
        }
        if (!observed.keySet().equals(requested)) {
            throw new IllegalArgumentException("missing or unexpected inference evidence");
        }
        java.util.ArrayList<Argument<V>> updates = new java.util.ArrayList<>(replacements);
        for (String hole : holes) updates.add(new Argument<>(hole, observed.get(hole)));
        return refine(schema, seed, updates);
    }

    private static <V> Map<String, V> bind(List<Formal<V>> schema,
            List<Argument<V>> seed, List<Argument<V>> replacements, boolean refine) {
        Map<String, Formal<V>> formals = new LinkedHashMap<>();
        for (Formal<V> formal : schema) {
            if (formals.putIfAbsent(formal.name(), formal) != null) {
                throw new IllegalArgumentException("duplicate formal: " + formal.name());
            }
        }
        Map<String, V> staged = checked(formals, seed);
        Map<String, V> updates = checked(formals, replacements);
        for (String name : updates.keySet()) {
            if (!refine && staged.containsKey(name)) {
                throw new IllegalArgumentException("duplicate argument: " + name);
            }
        }
        staged.putAll(updates);
        Map<String, V> canonical = new LinkedHashMap<>();
        for (String name : formals.keySet()) {
            if (staged.containsKey(name)) canonical.put(name, staged.get(name));
        }
        return Collections.unmodifiableMap(canonical);
    }

    private static <V> Map<String, V> checked(Map<String, Formal<V>> formals,
            List<Argument<V>> arguments) {
        Map<String, V> result = new LinkedHashMap<>();
        for (Argument<V> argument : arguments) {
            String name = argument.name();
            Formal<V> formal = formals.get(name);
            if (formal == null) throw new IllegalArgumentException("unknown formal: " + name);
            if (result.containsKey(name)) throw new IllegalArgumentException("duplicate argument: " + name);
            if (!formal.accepts().test(argument.value())) {
                throw new IllegalArgumentException("invalid argument kind or bound: " + name);
            }
            result.put(name, argument.value());
        }
        return result;
    }
}
