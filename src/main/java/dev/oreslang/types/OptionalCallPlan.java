package dev.oreslang.types;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compatibility view of a fixed call with omittable formal slots.
 *
 * <p>{@link CheckedCallPlan} is the canonical binder used by compiler and
 * runtime paths. This record remains as a narrow compatibility/test surface;
 * {@link #bind(List, List, List)} delegates to the canonical plan and projects
 * the omission-aware representation.</p>
 */
public record OptionalCallPlan(
        List<OptionalCallPlan.BoundSlot> sourceOrder,
        List<Integer> abiToSource,
        List<Boolean> omittable) {

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

    public OptionalCallPlan {
        sourceOrder = List.copyOf(sourceOrder);
        abiToSource = List.copyOf(abiToSource);
        omittable = List.copyOf(omittable);

        if (abiToSource.size() != omittable.size()) {
            throw new IllegalArgumentException(
                    "optional call plan formal/defaultability metadata size mismatch");
        }

        boolean[] seenSource = new boolean[sourceOrder.size()];
        boolean[] seenTarget = new boolean[abiToSource.size()];
        boolean namedStarted = false;
        int nextPositional = 0;
        Set<String> labels = new HashSet<>();
        Boolean provenanceMode = null;
        int previousCallItemIndex = -1;
        int previousProjectionIndex = -1;
        boolean previousProjectionWasNamed = false;

        for (int i = 0; i < sourceOrder.size(); i++) {
            BoundSlot slot = Objects.requireNonNull(sourceOrder.get(i), "bound slot");
            if (slot.sourceIndex() != i
                    || slot.sourceIndex() < 0
                    || slot.sourceIndex() >= seenSource.length
                    || seenSource[slot.sourceIndex()]) {
                throw new IllegalArgumentException(
                        "optional call plan does not preserve dense lexical source order");
            }
            if (slot.parameterIndex() < 0
                    || slot.parameterIndex() >= seenTarget.length
                    || seenTarget[slot.parameterIndex()]) {
                throw new IllegalArgumentException(
                        "optional call plan has duplicate/out-of-range formal slot");
            }
            if (slot.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument");
                }
                // Omission cannot skip an ABI slot within a positional prefix.
                // Only explicitly named arguments may omit earlier formals.
                if (slot.parameterIndex() != nextPositional++) {
                    throw new IllegalArgumentException(
                            "positional optional call slot skips or reorders a formal");
                }
            } else {
                namedStarted = true;
                if (slot.label().isBlank() || !labels.add(slot.label())) {
                    throw new IllegalArgumentException(
                            "duplicate or empty named argument in optional call plan");
                }
            }

            boolean provenanced = slot.callItemIndex() >= 0;
            if (slot.callItemIndex() < -1
                    || slot.projectionIndex() < -1
                    || (!provenanced && slot.projectionIndex() != -1)) {
                throw new IllegalArgumentException(
                        "invalid optional-call source provenance");
            }
            if (provenanceMode == null) {
                provenanceMode = provenanced;
            } else if (provenanceMode != provenanced) {
                throw new IllegalArgumentException(
                        "optional call plan cannot mix provenanced and unprovenanced inputs");
            }
            if (provenanced) {
                if (slot.callItemIndex() < previousCallItemIndex) {
                    throw new IllegalArgumentException(
                            "optional call provenance reorders source call items");
                }
                if (slot.callItemIndex() == previousCallItemIndex) {
                    // One raw call item cannot simultaneously be a tuple
                    // (unlabeled) and a record (labeled) spread.
                    if ((slot.label() != null) != previousProjectionWasNamed) {
                        throw new IllegalArgumentException(
                                "one call item cannot mix tuple and record projections");
                    }
                    // Mixed projection kinds for one source item cannot be
                    // produced by the parser; reject public-plan forgeries.
                    if ((slot.label() == null)
                            != (sourceOrder.get(i - 1).label() == null)) {
                        throw new IllegalArgumentException(
                                "optional call item mixes tuple and record projections");
                    }
                    if (slot.projectionIndex() < 0
                            || previousProjectionIndex < 0
                            || slot.projectionIndex() != previousProjectionIndex + 1) {
                        throw new IllegalArgumentException(
                                "optional call projection provenance must be contiguous and ordered");
                    }
                } else if (slot.projectionIndex() > 0) {
                    throw new IllegalArgumentException(
                            "optional call projection provenance must begin at projection 0");
                }
                previousCallItemIndex = slot.callItemIndex();
                previousProjectionIndex = slot.projectionIndex();
                previousProjectionWasNamed = slot.label() != null;
            }

            seenSource[slot.sourceIndex()] = true;
            seenTarget[slot.parameterIndex()] = true;
            if (!Objects.equals(
                    abiToSource.get(slot.parameterIndex()),
                    slot.sourceIndex())) {
                throw new IllegalArgumentException(
                        "optional call plan source/ABI maps disagree");
            }
        }

        for (int target = 0; target < abiToSource.size(); target++) {
            int source = abiToSource.get(target);
            if (source < -1 || source >= sourceOrder.size()) {
                throw new IllegalArgumentException(
                        "optional call plan contains invalid ABI source index");
            }
            if ((source == -1) == seenTarget[target]) {
                throw new IllegalArgumentException(
                        "optional call plan omitted/bound slot bookkeeping disagrees");
            }
            Boolean mayOmit = Objects.requireNonNull(
                    omittable.get(target), "omittable flag");
            if (source == -1 && !mayOmit) {
                throw new IllegalArgumentException(
                        "optional call plan omits required formal slot " + target);
            }
        }
    }

    public static OptionalCallPlan bind(
            List<String> parameterNames,
            List<Boolean> omittable,
            List<FixedProductCallPlan.InputSlot> inputs) {
        CheckedCallPlan checked =
                CheckedCallPlan.bind(
                        parameterNames,
                        omittable,
                        inputs.stream()
                                .map(input -> new CheckedCallPlan.InputSlot(
                                        input.label(),
                                        input.callItemIndex(),
                                        input.projectionIndex()))
                                .toList());
        List<BoundSlot> source =
                checked.sourceOrder().stream()
                        .map(slot -> new BoundSlot(
                                slot.sourceIndex(),
                                slot.parameterIndex(),
                                slot.label(),
                                slot.callItemIndex(),
                                slot.projectionIndex()))
                        .toList();
        return new OptionalCallPlan(
                source,
                checked.abiToSource(),
                checked.omittable());
    }


    public int suppliedCount() {
        return sourceOrder.size();
    }

    public int omittedCount() {
        int count = 0;
        for (int source : abiToSource) if (source == -1) count++;
        return count;
    }
}
