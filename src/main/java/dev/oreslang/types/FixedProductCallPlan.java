package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable compiler-only plan for a fixed positional/labeled product call.
 *
 * <p>Inputs are already flattened into lexical scalar-evaluation order. This
 * binder maps those scalar values to declaration-order ABI slots without
 * changing evaluation order. Tuple expansion therefore contributes positional
 * inputs while record expansion contributes named inputs.</p>
 *
 * <p>This is deliberately not runtime dispatch. The selected callable, types,
 * effects, ownership, borrowing, suspension and aggregate provenance must be
 * checked by compiler passes before an executable lowering consumes this plan.</p>
 */
public record FixedProductCallPlan(
        List<FixedProductCallPlan.BoundSlot> sourceOrder,
        List<Integer> abiToSource) {

    /**
     * A flattened scalar slot; null label means positional.
     *
     * <p>{@code callItemIndex}/{@code projectionIndex} preserve the origin of
     * each evaluated scalar. A direct scalar uses projection {@code -1}; a
     * tuple/record spread uses projection {@code 0..n-1}. The legacy
     * one-argument constructor intentionally creates an unprovenanced slot for
     * pure binder tests and non-compiler callers.</p>
     */
    public record InputSlot(
            String label,
            int callItemIndex,
            int projectionIndex) {
        public InputSlot {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException("named argument label cannot be empty");
            }
            if (callItemIndex < -1 || projectionIndex < -1) {
                throw new IllegalArgumentException("call-item provenance index cannot be below -1");
            }
            if (callItemIndex == -1 && projectionIndex != -1) {
                throw new IllegalArgumentException(
                        "projection provenance requires a source call-item index");
            }
        }

        public InputSlot(String label) {
            this(label, -1, -1);
        }

        public static InputSlot positional() {
            return new InputSlot(null);
        }

        public static InputSlot positional(int callItemIndex) {
            return new InputSlot(null, callItemIndex, -1);
        }

        public static InputSlot positionalProjection(
                int callItemIndex,
                int projectionIndex) {
            return new InputSlot(null, callItemIndex, projectionIndex);
        }

        public static InputSlot named(String label) {
            return new InputSlot(Objects.requireNonNull(label, "label"));
        }

        public static InputSlot named(String label, int callItemIndex) {
            return new InputSlot(
                    Objects.requireNonNull(label, "label"),
                    callItemIndex,
                    -1);
        }

        public static InputSlot namedProjection(
                String label,
                int callItemIndex,
                int projectionIndex) {
            return new InputSlot(
                    Objects.requireNonNull(label, "label"),
                    callItemIndex,
                    projectionIndex);
        }

        public boolean hasProvenance() {
            return callItemIndex >= 0;
        }
    }

    /** One source scalar and its declaration-order target parameter. */
    public record BoundSlot(
            int sourceIndex,
            int parameterIndex,
            String label,
            int callItemIndex,
            int projectionIndex) {
        public BoundSlot(int sourceIndex, int parameterIndex, String label) {
            this(sourceIndex, parameterIndex, label, -1, -1);
        }
    }

    public FixedProductCallPlan {
        sourceOrder = List.copyOf(sourceOrder);
        abiToSource = List.copyOf(abiToSource);
        int arity = sourceOrder.size();
        if (arity != abiToSource.size()) {
            throw new IllegalArgumentException("product call plan must be a total permutation");
        }

        boolean[] claimedTargets = new boolean[arity];
        boolean[] claimedSources = new boolean[arity];
        boolean namedStarted = false;
        Set<String> claimedLabels = new HashSet<>();
        Boolean provenanceMode = null;
        int previousCallItemIndex = -1;
        int previousProjectionIndex = -1;
        for (int i = 0; i < arity; i++) {
            BoundSlot slot = Objects.requireNonNull(sourceOrder.get(i), "bound slot");
            int source = slot.sourceIndex();
            int target = slot.parameterIndex();
            String label = slot.label();

            if (label == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument or record spread");
                }
                if (target != i) {
                    throw new IllegalArgumentException(
                            "positional product call slot is not in its leading parameter position");
                }
            } else {
                namedStarted = true;
                if (label.isBlank() || !claimedLabels.add(label)) {
                    throw new IllegalArgumentException(
                            "duplicate or empty named argument in product call plan: " + label);
                }
            }

            if (source < 0 || source >= arity || target < 0 || target >= arity
                    || claimedSources[source] || claimedTargets[target]) {
                throw new IllegalArgumentException("invalid product call plan permutation");
            }

            boolean provenanced = slot.callItemIndex() >= 0;
            if (slot.callItemIndex() < -1
                    || slot.projectionIndex() < -1
                    || (!provenanced && slot.projectionIndex() != -1)) {
                throw new IllegalArgumentException(
                        "invalid product call source provenance");
            }
            if (provenanceMode == null) {
                provenanceMode = provenanced;
            } else if (provenanceMode != provenanced) {
                throw new IllegalArgumentException(
                        "product call plan cannot mix provenanced and unprovenanced inputs");
            }
            if (provenanced) {
                if (slot.callItemIndex() < previousCallItemIndex) {
                    throw new IllegalArgumentException(
                            "product call provenance reorders source call items");
                }
                if (slot.callItemIndex() == previousCallItemIndex) {
                    if (slot.projectionIndex() < 0
                            || previousProjectionIndex < 0
                            || slot.projectionIndex() <= previousProjectionIndex) {
                        throw new IllegalArgumentException(
                                "product call projection provenance is not strictly ordered");
                    }
                } else if (slot.projectionIndex() > 0) {
                    throw new IllegalArgumentException(
                            "product call projection provenance must begin at projection 0");
                }
                previousCallItemIndex = slot.callItemIndex();
                previousProjectionIndex = slot.projectionIndex();
            }

            claimedSources[source] = true;
            claimedTargets[target] = true;
            if (source != i || !Objects.equals(abiToSource.get(target), source)) {
                throw new IllegalArgumentException(
                        "product call plan disagrees with lexical evaluation order or ABI mapping");
            }
        }
    }

    /**
     * Bind an already-flattened fixed product to a selected callable.
     *
     * <p>Overload selection must happen before this operation. Parameter labels
     * are binding metadata, never overload-dispatch keys.</p>
     */
    public static FixedProductCallPlan bind(
            List<String> parameterNames,
            List<InputSlot> inputs) {
        Objects.requireNonNull(parameterNames, "parameterNames");
        Objects.requireNonNull(inputs, "inputs");

        Map<String, Integer> parameterIndexByName = new HashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String name = Objects.requireNonNull(parameterNames.get(i), "parameter name");
            if (name.isBlank() || parameterIndexByName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException(
                        "duplicate or empty declared parameter name: " + name);
            }
        }

        if (inputs.size() != parameterNames.size()) {
            throw new IllegalArgumentException(
                    "expanded call arity mismatch: expected "
                            + parameterNames.size() + ", got " + inputs.size());
        }

        boolean namedStarted = false;
        int nextPositional = 0;
        Set<Integer> claimed = new HashSet<>();
        List<BoundSlot> bound = new ArrayList<>(inputs.size());
        Integer[] abiToSource = new Integer[parameterNames.size()];

        for (int sourceIndex = 0; sourceIndex < inputs.size(); sourceIndex++) {
            InputSlot input = Objects.requireNonNull(inputs.get(sourceIndex), "input slot");
            int target;
            if (input.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument or record spread");
                }
                target = nextPositional++;
            } else {
                namedStarted = true;
                Integer declared = parameterIndexByName.get(input.label());
                if (declared == null) {
                    throw new IllegalArgumentException(
                            "unknown named argument '" + input.label() + "'");
                }
                target = declared;
            }

            if (!claimed.add(target)) {
                throw new IllegalArgumentException(
                        "duplicate binding for parameter '" + parameterNames.get(target) + "'");
            }
            bound.add(new BoundSlot(
                    sourceIndex,
                    target,
                    input.label(),
                    input.callItemIndex(),
                    input.projectionIndex()));
            abiToSource[target] = sourceIndex;
        }

        for (int i = 0; i < abiToSource.length; i++) {
            if (abiToSource[i] == null) {
                throw new IllegalArgumentException(
                        "missing argument for parameter '" + parameterNames.get(i) + "'");
            }
        }
        return new FixedProductCallPlan(bound, List.of(abiToSource));
    }
}
