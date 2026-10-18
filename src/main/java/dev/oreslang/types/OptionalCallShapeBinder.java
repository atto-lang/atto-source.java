package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A source-order-preserving, type-agnostic binding plan for named and optional
 * arguments. This is a pre-integration compiler building block: parser, AST,
 * type/ownership checking, and both runtimes must be integrated before the
 * surface syntax can be enabled.
 *
 * <p>Argument expressions MUST be evaluated exactly once in the original
 * source order before being placed into parameter order according to this plan.
 * A -1 source index means the parameter was omitted and must be lowered to
 * a typed Option.None, not null, undefined or a default argument expression.
 */
public final class OptionalCallShapeBinder {
    private OptionalCallShapeBinder() { }

    /** The call-site label is null for a positional argument. */
    public record Argument(String label) {
        public Argument {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException("named argument label cannot be blank");
            }
        }
        public static Argument positional() { return new Argument(null); }
        public static Argument named(String name) {
            return new Argument(Objects.requireNonNull(name, "name"));
        }
    }

    public record Parameter(String name, boolean optional) {
        public Parameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("parameter name cannot be blank");
            }
        }
        public static Parameter required(String name) { return new Parameter(name, false); }
        public static Parameter optional(String name) { return new Parameter(name, true); }
    }

    /** Position in the argument-source list for each declared parameter. */
    public record Plan(List<Integer> sourceIndexByParameter) {
        public Plan {
            sourceIndexByParameter = List.copyOf(sourceIndexByParameter);
        }
        public int declaredArity() { return sourceIndexByParameter.size(); }
        public boolean omitted(int parameterIndex) {
            return sourceIndexByParameter.get(parameterIndex) == -1;
        }
        public int sourceIndex(int parameterIndex) {
            return sourceIndexByParameter.get(parameterIndex);
        }
    }

    /** The selected declaration index and its permutation; never a runtime overload choice. */
    public record Selection(int signatureIndex, Plan plan) { }

    /**
     * Bind without executing or reordering a single expression.
     *
     * <p>Ordinary positional arguments always claim the next leading slot.
     * A middle optional slot can be omitted through a label, not by positional
     * hole shifting. Following the first named argument, positional arguments
     * are forbidden; spreads must be rejected by the frontend before this call.
     */
    public static Plan bind(List<Parameter> parameters, List<Argument> arguments) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Integer> parameterIndex = checkedParameters(parameters);

        int[] binding = new int[parameters.size()];
        Arrays.fill(binding, -1);
        int nextPositional = 0;
        boolean seenNamed = false;
        for (int sourceIndex = 0; sourceIndex < arguments.size(); sourceIndex++) {
            Argument argument = Objects.requireNonNull(arguments.get(sourceIndex), "argument");
            int slot;
            if (argument.label() == null) {
                if (seenNamed) {
                    throw new IllegalArgumentException("positional argument after named argument");
                }
                if (nextPositional >= parameters.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
                slot = nextPositional++;
            } else {
                seenNamed = true;
                Integer namedSlot = parameterIndex.get(argument.label());
                if (namedSlot == null) {
                    throw new IllegalArgumentException("unknown named argument '" + argument.label() + "'");
                }
                slot = namedSlot;
            }
            if (binding[slot] != -1) {
                throw new IllegalArgumentException(
                        "parameter '" + parameters.get(slot).name() + "' was supplied more than once");
            }
            binding[slot] = sourceIndex;
        }

        List<Integer> permutation = new ArrayList<>(binding.length);
        for (int i = 0; i < binding.length; i++) {
            if (binding[i] == -1 && !parameters.get(i).optional()) {
                throw new IllegalArgumentException(
                        "missing required argument '" + parameters.get(i).name() + "'");
            }
            permutation.add(binding[i]);
        }
        return new Plan(permutation);
    }

    private static Map<String, Integer> checkedParameters(List<Parameter> parameters) {
        Map<String, Integer> parameterIndex = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = Objects.requireNonNull(parameters.get(i), "parameter");
            if (parameterIndex.putIfAbsent(parameter.name(), i) != null) {
                throw new IllegalArgumentException("duplicate parameter name '" + parameter.name() + "'");
            }
        }
        return parameterIndex;
    }

    /**
     * Conservative v1 admission check for an existing callable overload domain.
     * An optional declaration accepts a supplied-argument COUNT interval
     * [number of required slots, total slots]. Any interval intersecting
     * another declaration's interval is rejected at declaration/link time,
     * even if their label sets appear disjoint. This preserves deterministic
     * arity-only dispatch until cross-backend call-shape proofs exist.
     *
     * <p>Nonoptional/nonoptional signature collision rules (including the
     * separately staged nominal enum exception) remain with the caller.
     */
    public static void requireNonOverlappingOptionalArityIntervals(
            List<List<Parameter>> signatures) {
        Objects.requireNonNull(signatures, "signatures");
        for (List<Parameter> signature : signatures) {
            checkedParameters(Objects.requireNonNull(signature, "signature"));
        }
        for (int i = 0; i < signatures.size(); i++) {
            List<Parameter> a = signatures.get(i);
            long aRequired = a.stream().filter(p -> !p.optional()).count();
            for (int j = i + 1; j < signatures.size(); j++) {
                List<Parameter> b = signatures.get(j);
                long bRequired = b.stream().filter(p -> !p.optional()).count();
                if (aRequired == a.size() && bRequired == b.size()) {
                    continue; // ordinary exact-arity policy remains separate
                }
                if (Math.max(aRequired, bRequired) <= Math.min(a.size(), b.size())) {
                    throw new IllegalArgumentException(
                            "overlapping optional supplied-arity intervals for declarations "
                                    + i + " and " + j);
                }
            }
        }
    }

    /**
     * Resolve a family structurally, by labels and required slots ONLY.
     * Full declared arity is retained for the ordinary callable ABI selector.
     * If omitted optionals make two declarations match, reject ambiguity;
     * neither argument types nor an "exact arity wins" preference may hide it.
     *
     * <p>Signature ownership, visibility, overrides and constructor-receiver
     * erasure must already be validated by the calling compiler phase.
     */
    public static Selection bindUnique(
            List<List<Parameter>> signatures,
            List<Argument> arguments) {
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(arguments, "arguments");
        requireNonOverlappingOptionalArityIntervals(signatures);
        Selection selected = null;
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < signatures.size(); i++) {
            // Malformed declarations are errors, not silently skipped candidates.
            checkedParameters(Objects.requireNonNull(signatures.get(i), "signature"));
            try {
                Plan plan = bind(signatures.get(i), arguments);
                selected = new Selection(i, plan);
                valid.add(i);
            } catch (IllegalArgumentException ignored) {
                // The whole family is checked below. A nonmatching candidate
                // must not win solely because its parameter types differ.
            }
        }
        if (valid.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous optional/named call shape matches declarations " + valid);
        }
        if (selected == null) {
            if (signatures.size() == 1) {
                // Preserve useful exact diagnostic for single-signature calls.
                bind(signatures.getFirst(), arguments);
            }
            throw new IllegalArgumentException(
                    "no declared callable has a compatible required/named argument shape");
        }
        return selected;
    }
}
