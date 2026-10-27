package dev.oreslang.types;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compatibility view of a required fixed product call.
 *
 * <p>{@link CheckedCallPlan} is the canonical binder used by compiler and
 * runtime paths. This record remains as a narrow compatibility/test surface;
 * {@link #bind(List, List)} delegates to the canonical plan and then projects
 * the required-call representation.</p>
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
                    // A single source spread cannot be tuple and record
                    // projections simultaneously, even in legacy plan views.
                    if ((slot.label() == null)
                            != (sourceOrder.get(i - 1).label() == null)) {
                        throw new IllegalArgumentException(
                                "product call item mixes tuple and record projections");
                    }
                    if (slot.projectionIndex() < 0
                            || previousProjectionIndex < 0
                            || slot.projectionIndex() != previousProjectionIndex + 1) {
                        throw new IllegalArgumentException(
                                "product call projection provenance must be contiguous and ordered");
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
        // Required fixed calls have an exact arity contract. Keep this
        // diagnostic stable when delegating to the omission-aware binder.
        // The canonical binder must still enforce all label/provenance rules.
        if (parameterNames.size() != inputs.size()) {
            throw new IllegalArgumentException(
                    "fixed product call arity mismatch: expected "
                            + parameterNames.size() + ", got " + inputs.size());
        }
        CheckedCallPlan checked =
                CheckedCallPlan.required(
                        parameterNames,
                        inputs.stream()
                                .map(input -> new CheckedCallPlan.InputSlot(
                                        input.label(),
                                        input.callItemIndex(),
                                        input.projectionIndex()))
                                .toList());
        List<BoundSlot> bound =
                checked.sourceOrder().stream()
                        .map(slot -> new BoundSlot(
                                slot.sourceIndex(),
                                slot.parameterIndex(),
                                slot.label(),
                                slot.callItemIndex(),
                                slot.projectionIndex()))
                        .toList();
        return new FixedProductCallPlan(
                bound,
                checked.abiToSource());
    }
}
