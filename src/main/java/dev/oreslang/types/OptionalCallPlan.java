package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-only plan for fixed calls with omittable formal slots.
 *
 * <p>Source expressions remain in lexical order. {@code abiToSource[i] == -1}
 * means formal slot {@code i} is omitted and must be filled from the
 * declaration's checked default. No omitted slot corresponds to an evaluated
 * source expression.</p>
 */
public record OptionalCallPlan(
        List<OptionalCallPlan.BoundSlot> sourceOrder,
        List<Integer> abiToSource,
        List<Boolean> omittable) {

    public record BoundSlot(int sourceIndex, int parameterIndex, String label) { }

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
        Set<String> labels = new HashSet<>();

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
            } else {
                namedStarted = true;
                if (slot.label().isBlank() || !labels.add(slot.label())) {
                    throw new IllegalArgumentException(
                            "duplicate or empty named argument in optional call plan");
                }
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
        Objects.requireNonNull(parameterNames, "parameterNames");
        Objects.requireNonNull(omittable, "omittable");
        Objects.requireNonNull(inputs, "inputs");
        if (parameterNames.size() != omittable.size()) {
            throw new IllegalArgumentException(
                    "parameter/defaultability metadata size mismatch");
        }
        if (inputs.size() > parameterNames.size()) {
            throw new IllegalArgumentException(
                    "too many supplied arguments: expected at most "
                            + parameterNames.size() + ", got " + inputs.size());
        }

        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String name = Objects.requireNonNull(
                    parameterNames.get(i), "parameter name");
            if (name.isBlank() || byName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException(
                        "duplicate or empty declared parameter name: " + name);
            }
            Objects.requireNonNull(omittable.get(i), "omittable flag");
        }

        Integer[] abi = new Integer[parameterNames.size()];
        java.util.Arrays.fill(abi, -1);
        List<BoundSlot> source = new ArrayList<>(inputs.size());
        Set<Integer> claimed = new HashSet<>();
        boolean namedStarted = false;
        int nextPositional = 0;

        for (int sourceIndex = 0; sourceIndex < inputs.size(); sourceIndex++) {
            FixedProductCallPlan.InputSlot input =
                    Objects.requireNonNull(inputs.get(sourceIndex), "input slot");
            int target;
            if (input.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument");
                }
                target = nextPositional++;
            } else {
                namedStarted = true;
                Integer found = byName.get(input.label());
                if (found == null) {
                    throw new IllegalArgumentException(
                            "unknown named argument '" + input.label() + "'");
                }
                target = found;
            }

            if (target >= parameterNames.size()) {
                throw new IllegalArgumentException("too many positional arguments");
            }
            if (!claimed.add(target)) {
                throw new IllegalArgumentException(
                        "duplicate binding for parameter '"
                                + parameterNames.get(target) + "'");
            }
            abi[target] = sourceIndex;
            source.add(new BoundSlot(sourceIndex, target, input.label()));
        }

        for (int i = 0; i < parameterNames.size(); i++) {
            if (abi[i] == -1 && !omittable.get(i)) {
                throw new IllegalArgumentException(
                        "missing required argument for parameter '"
                                + parameterNames.get(i) + "'");
            }
        }

        return new OptionalCallPlan(source, List.of(abi), omittable);
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
