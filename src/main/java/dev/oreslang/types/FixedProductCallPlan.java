package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, compiler-only plan for finite positional/named product expansion.
 *
 * <p>Arguments are presented in lexical scalar-evaluation order. This binder
 * assigns them to declaration slots without reordering their evaluation.
 * Neither structural shape nor labels select an overload: the caller must
 * choose a unique callable by its ordinary arity/nominal rules first.</p>
 *
 * <p>This utility is deliberately not a runtime dispatch mechanism. The
 * expression, ownership, effect, borrow, and suspension checkers must prove
 * every input before consuming this mapping.</p>
 */
public final class FixedProductCallPlan {
    private final List<BoundSlot> sourceOrder;
    private final List<Integer> abiToSource;

    public List<BoundSlot> sourceOrder() { return sourceOrder; }
    public List<Integer> abiToSource() { return abiToSource; }
    /** A flattened scalar slot; null label means positional. */
    public record InputSlot(String label) {
        public InputSlot {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException("named argument label cannot be empty");
            }
        }
        public static InputSlot positional() { return new InputSlot(null); }
        public static InputSlot named(String label) {
            return new InputSlot(Objects.requireNonNull(label, "label"));
        }
    }

    /** Mapping retains scalar source index for evaluation/move tracking. */
    public record BoundSlot(int sourceIndex, int parameterIndex, String label) { }

    // Only bind() knows the selected callee's declared parameter labels.
    // A public constructor would let callers forge a plausible total
    // permutation that binds label "a" to the ABI slot for "b".
    private FixedProductCallPlan(List<BoundSlot> sourceOrder, List<Integer> abiToSource) {
        sourceOrder = List.copyOf(sourceOrder);
        abiToSource = List.copyOf(abiToSource);
        int arity = sourceOrder.size();
        if (arity != abiToSource.size()) {
            throw new IllegalArgumentException("product call plan must be a total permutation");
        }
        // Retain internal permutation invariants even though only bind()
        // may construct a checked plan.
        boolean[] claimedTargets = new boolean[arity];
        boolean[] claimedSources = new boolean[arity];
        boolean namedStarted = false;
        Set<String> claimedLabels = new HashSet<>();
        for (int i = 0; i < arity; i++) {
            BoundSlot slot = Objects.requireNonNull(sourceOrder.get(i), "bound slot");
            int source = slot.sourceIndex();
            int target = slot.parameterIndex();
            // A total ABI permutation is insufficient unless source-order
            // argument grammar has also been verified.
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
            claimedSources[source] = true;
            claimedTargets[target] = true;
            if (source != i || !Objects.equals(abiToSource.get(target), source)) {
                throw new IllegalArgumentException(
                        "product call plan disagrees with lexical evaluation order or ABI mapping");
            }
        }
        this.sourceOrder = sourceOrder;
        this.abiToSource = abiToSource;
    }

    @Override public boolean equals(Object other) {
        return other instanceof FixedProductCallPlan plan
                && sourceOrder.equals(plan.sourceOrder)
                && abiToSource.equals(plan.abiToSource);
    }

    @Override public int hashCode() {
        return Objects.hash(sourceOrder, abiToSource);
    }

    @Override public String toString() {
        return "FixedProductCallPlan[sourceOrder=" + sourceOrder
                + ", abiToSource=" + abiToSource + "]";
    }

    /**
     * Bind already-flattened positional/record fields against a selected
     * callable's stable parameter names. All inputs are fixed/closed products.
     */
    public static FixedProductCallPlan bind(List<String> parameterNames, List<InputSlot> inputs) {
        // Freeze the caller's collections before arity checks and slot binding.
        // Otherwise a mutable/custom List can change between the name-map and
        // source-slot traversals, producing a plan for no coherent call shape.
        parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "parameterNames"));
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        Map<String, Integer> parameterIndexByName = new HashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String name = Objects.requireNonNull(parameterNames.get(i), "parameter name");
            if (name.isBlank() || parameterIndexByName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException("duplicate or empty declared parameter name: " + name);
            }
        }

        if (inputs.size() != parameterNames.size()) {
            throw new IllegalArgumentException("expanded call arity mismatch: expected "
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
                    throw new IllegalArgumentException("unknown named argument '" + input.label() + "'");
                }
                target = declared;
            }
            if (!claimed.add(target)) {
                throw new IllegalArgumentException(
                        "duplicate binding for parameter '" + parameterNames.get(target) + "'");
            }
            bound.add(new BoundSlot(sourceIndex, target, input.label()));
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
