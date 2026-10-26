package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, source-order-preserving binder for statically named value-call arguments.
 *
 * <p>This does not enable Java named-call syntax. A future parser and checker must
 * provide static formal labels and an already-expanded, validated argument list.
 * Runtime map lookup and spreading dynamic records are not part of this contract.
 */
public final class NamedCallBindingPlan {
    private NamedCallBindingPlan() { }

    /**
     * @param formalNames declaration-order parameter names
     * @param sourceLabels source-order labels; null denotes a positional argument
     */
    public static Plan bind(List<String> formalNames, List<String> sourceLabels) {
        Objects.requireNonNull(formalNames, "formalNames");
        Objects.requireNonNull(sourceLabels, "sourceLabels");
        Map<String, Integer> formalIndex = new HashMap<>();
        for (int i = 0; i < formalNames.size(); i++) {
            String name = formalNames.get(i);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("empty formal parameter name at position " + (i + 1));
            }
            if (formalIndex.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException("duplicate formal parameter name '" + name + "'");
            }
        }

        int[] formalToSource = new int[formalNames.size()];
        Arrays.fill(formalToSource, -1);
        List<Integer> sourceToFormal = new ArrayList<>(sourceLabels.size());
        int nextPositional = 0;
        boolean seenNamed = false;
        for (int source = 0; source < sourceLabels.size(); source++) {
            String label = sourceLabels.get(source);
            int formal;
            if (label == null) {
                if (seenNamed) {
                    throw new IllegalArgumentException("positional argument after named argument");
                }
                formal = nextPositional++;
                if (formal >= formalNames.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
            } else {
                seenNamed = true;
                if (label.isBlank()) {
                    throw new IllegalArgumentException("empty named argument label");
                }
                Integer found = formalIndex.get(label);
                if (found == null) {
                    throw new IllegalArgumentException("unknown named argument '" + label + "'");
                }
                formal = found;
            }
            if (formalToSource[formal] != -1) {
                throw new IllegalArgumentException("duplicate argument for parameter '"
                        + formalNames.get(formal) + "'");
            }
            formalToSource[formal] = source;
            sourceToFormal.add(formal);
        }
        for (int formal = 0; formal < formalToSource.length; formal++) {
            if (formalToSource[formal] < 0) {
                throw new IllegalArgumentException("missing argument for parameter '"
                        + formalNames.get(formal) + "'");
            }
        }
        List<Integer> reversed = new ArrayList<>(formalToSource.length);
        for (int source : formalToSource) reversed.add(source);
        return new Plan(List.copyOf(sourceToFormal), List.copyOf(reversed));
    }

    /** Immutable slot permutation; expressions must already have evaluated once in source order. */
    public record Plan(List<Integer> sourceToFormal, List<Integer> formalToSource) {
        public Plan {
            sourceToFormal = List.copyOf(sourceToFormal);
            formalToSource = List.copyOf(formalToSource);
            if (sourceToFormal.size() != formalToSource.size()) {
                throw new IllegalArgumentException("invalid argument permutation");
            }
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < sourceToFormal.size(); i++) {
                Integer slot = sourceToFormal.get(i);
                if (slot == null || slot < 0 || slot >= sourceToFormal.size()
                        || !seen.add(slot) || !formalToSource.get(slot).equals(i)) {
                    throw new IllegalArgumentException("invalid argument permutation");
                }
            }
        }

        /** Only rearrange previously evaluated values; never evaluate expressions here. */
        public <T> List<T> inFormalOrder(List<T> evaluatedInSourceOrder) {
            Objects.requireNonNull(evaluatedInSourceOrder, "evaluatedInSourceOrder");
            if (evaluatedInSourceOrder.size() != sourceToFormal.size()) {
                throw new IllegalArgumentException("evaluated argument count mismatch");
            }
            List<T> ordered = new ArrayList<>(Collections.nCopies(formalToSource.size(), null));
            for (int formal = 0; formal < formalToSource.size(); formal++) {
                ordered.set(formal, evaluatedInSourceOrder.get(formalToSource.get(formal)));
            }
            return Collections.unmodifiableList(ordered);
        }
    }
}
