package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical immutable binding plan for a checked fixed call shape.
 *
 * <p>The raw input slots are already flattened into lexical scalar-evaluation
 * order. A tuple spread contributes positional projections and a record spread
 * contributes labeled projections. This plan is the single normalized
 * source-to-formal mapping consumed after callable selection by type checking,
 * ownership checking and executable lowering.</p>
 *
 * <p>{@code abiToSource[i] == -1} means formal slot {@code i} is omitted.
 * Omitted formals never correspond to a guest source expression and therefore
 * cannot contribute effects, ownership transfers or generic inference.</p>
 */
public record CheckedCallPlan(
        List<String> parameterNames,
        List<Boolean> omittable,
        List<CheckedCallPlan.BoundSlot> sourceOrder,
        List<Integer> abiToSource) {

    /**
     * One flattened scalar before formal binding.
     *
     * <p>A direct scalar uses projection {@code -1}. Tuple/record expansion
     * uses projection {@code 0..n-1}; record projections also carry a label.
     * The optional one-argument constructor is retained only for pure binder
     * tests that intentionally do not model compiler provenance.</p>
     */
    public record InputSlot(
            String label,
            int callItemIndex,
            int projectionIndex) {
        public InputSlot {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException(
                        "named argument label cannot be empty");
            }
            if (callItemIndex < -1 || projectionIndex < -1) {
                throw new IllegalArgumentException(
                        "call-item provenance index cannot be below -1");
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
            return new InputSlot(
                    null, callItemIndex, projectionIndex);
        }

        public static InputSlot named(String label) {
            return new InputSlot(
                    Objects.requireNonNull(label, "label"));
        }

        public static InputSlot named(
                String label,
                int callItemIndex) {
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

    private static final java.lang.ref.ReferenceQueue<Ast.Expr> CLEARED_CALL_SITES =
            new java.lang.ref.ReferenceQueue<>();
    private static final Map<IdentityKey, CheckedCallPlan> CHECKED_CALL_SITES =
            new HashMap<>();

    private static final class IdentityKey
            extends java.lang.ref.WeakReference<Ast.Expr> {
        private final int hash;

        private IdentityKey(
                Ast.Expr expression,
                java.lang.ref.ReferenceQueue<Ast.Expr> queue) {
            super(Objects.requireNonNull(expression, "checked call site"), queue);
            hash = System.identityHashCode(expression);
        }

        @Override public int hashCode() {
            return hash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Expr value = get();
            return value != null && value == key.get();
        }
    }

    private static void expungeCheckedCallSites() {
        java.lang.ref.Reference<? extends Ast.Expr> cleared;
        while ((cleared = CLEARED_CALL_SITES.poll()) != null) {
            CHECKED_CALL_SITES.remove(cleared);
        }
    }

    public static synchronized void record(
            Ast.Expr callSite,
            CheckedCallPlan plan) {
        Objects.requireNonNull(callSite, "checked call site");
        Objects.requireNonNull(plan, "checked call plan");
        if (!(callSite instanceof Ast.CallExpr)
                && !(callSite instanceof Ast.NewExpr)) {
            throw new IllegalArgumentException(
                    "checked call plans may attach only to CallExpr or NewExpr");
        }
        expungeCheckedCallSites();
        IdentityKey key =
                new IdentityKey(callSite, CLEARED_CALL_SITES);
        CheckedCallPlan old =
                CHECKED_CALL_SITES.putIfAbsent(key, plan);
        if (old != null && !old.equals(plan)) {
            throw new IllegalStateException(
                    "same call-site AST identity received inconsistent checked call plans");
        }
    }

    public static synchronized CheckedCallPlan lookup(
            Ast.Expr callSite) {
        Objects.requireNonNull(callSite, "checked call site");
        expungeCheckedCallSites();
        return CHECKED_CALL_SITES.get(
                new IdentityKey(callSite, null));
    }

    public static CheckedCallPlan require(
            Ast.Expr callSite) {
        CheckedCallPlan plan = lookup(callSite);
        if (plan == null) {
            throw new IllegalStateException(
                    "missing checked call plan for admitted call-site AST identity");
        }
        return plan;
    }

    public static void copyCheckedMetadata(
            Ast.Expr source,
            Ast.Expr target) {
        Objects.requireNonNull(source, "source checked call site");
        Objects.requireNonNull(target, "target checked call site");
        boolean sameKind =
                source instanceof Ast.CallExpr && target instanceof Ast.CallExpr
                        || source instanceof Ast.NewExpr && target instanceof Ast.NewExpr;
        if (!sameKind) return;

        CheckedCallPlan plan = lookup(source);
        if (plan != null && preservesRawBindingShape(source, target)) {
            record(target, plan);
        }
    }

    private static boolean preservesRawBindingShape(
            Ast.Expr source,
            Ast.Expr target) {
        List<Ast.Expr> sourceArguments =
                source instanceof Ast.CallExpr call
                        ? call.arguments()
                        : ((Ast.NewExpr) source).arguments();
        List<Ast.Expr> targetArguments =
                target instanceof Ast.CallExpr call
                        ? call.arguments()
                        : ((Ast.NewExpr) target).arguments();
        if (sourceArguments.size() != targetArguments.size()) return false;

        for (int i = 0; i < sourceArguments.size(); i++) {
            Ast.Expr before = sourceArguments.get(i);
            Ast.Expr after = targetArguments.get(i);
            if (before instanceof Ast.NamedArgExpr named) {
                if (!(after instanceof Ast.NamedArgExpr replacement)
                        || !named.name().equals(replacement.name())) {
                    return false;
                }
            } else if (before instanceof Ast.SpreadExpr) {
                if (!(after instanceof Ast.SpreadExpr)) return false;
            } else if (after instanceof Ast.NamedArgExpr
                    || after instanceof Ast.SpreadExpr) {
                return false;
            }
        }
        return true;
    }

    /** One supplied scalar in lexical evaluation order and its formal ABI slot. */
    public record BoundSlot(
            int sourceIndex,
            int parameterIndex,
            String label,
            int callItemIndex,
            int projectionIndex) { }

    public CheckedCallPlan {
        parameterNames = List.copyOf(parameterNames);
        omittable = List.copyOf(omittable);
        sourceOrder = List.copyOf(sourceOrder);
        abiToSource = List.copyOf(abiToSource);

        if (parameterNames.size() != omittable.size()
                || parameterNames.size() != abiToSource.size()) {
            throw new IllegalArgumentException(
                    "checked call plan formal metadata size mismatch");
        }

        Map<String, Integer> parameterIndexByName = new HashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String name = Objects.requireNonNull(
                    parameterNames.get(i), "parameter name");
            if (name.isBlank()
                    || parameterIndexByName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException(
                        "duplicate or empty declared parameter name: " + name);
            }
            Objects.requireNonNull(
                    omittable.get(i), "omittable flag");
        }

        boolean[] seenSource = new boolean[sourceOrder.size()];
        boolean[] seenTarget = new boolean[parameterNames.size()];
        boolean namedStarted = false;
        int nextPositional = 0;
        Set<String> seenLabels = new HashSet<>();
        Boolean provenanceMode = null;
        int previousCallItemIndex = -1;
        int previousProjectionIndex = -1;
        boolean previousProjectionWasNamed = false;

        for (int i = 0; i < sourceOrder.size(); i++) {
            BoundSlot slot =
                    Objects.requireNonNull(sourceOrder.get(i), "bound slot");
            if (slot.sourceIndex() != i
                    || slot.sourceIndex() < 0
                    || slot.sourceIndex() >= seenSource.length
                    || seenSource[slot.sourceIndex()]) {
                throw new IllegalArgumentException(
                        "checked call plan does not preserve dense lexical source order");
            }
            if (slot.parameterIndex() < 0
                    || slot.parameterIndex() >= parameterNames.size()
                    || seenTarget[slot.parameterIndex()]) {
                throw new IllegalArgumentException(
                        "checked call plan has duplicate/out-of-range formal slot");
            }

            if (slot.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument or record spread");
                }
                if (slot.parameterIndex() != nextPositional++) {
                    throw new IllegalArgumentException(
                            "positional argument skips or reorders a formal slot");
                }
            } else {
                namedStarted = true;
                if (slot.label().isBlank() || !seenLabels.add(slot.label())) {
                    throw new IllegalArgumentException(
                            "duplicate or empty named argument in checked call plan");
                }
                if (!parameterNames.get(slot.parameterIndex())
                        .equals(slot.label())) {
                    throw new IllegalArgumentException(
                            "checked call label/formal mapping disagrees for '"
                                    + slot.label() + "'");
                }
            }

            boolean provenanced = slot.callItemIndex() >= 0;
            if (slot.callItemIndex() < -1
                    || slot.projectionIndex() < -1
                    || (!provenanced && slot.projectionIndex() != -1)) {
                throw new IllegalArgumentException(
                        "invalid checked-call source provenance");
            }
            if (provenanceMode == null) {
                provenanceMode = provenanced;
            } else if (provenanceMode != provenanced) {
                throw new IllegalArgumentException(
                        "checked call plan cannot mix provenanced and unprovenanced inputs");
            }
            if (provenanced) {
                if (slot.callItemIndex() < previousCallItemIndex) {
                    throw new IllegalArgumentException(
                            "checked call provenance reorders source call items");
                }
                if (slot.callItemIndex() == previousCallItemIndex) {
                    // One raw call item cannot simultaneously be a tuple
                    // (unlabeled) and a record (labeled) spread.
                    if ((slot.label() != null) != previousProjectionWasNamed) {
                        throw new IllegalArgumentException(
                                "one call item cannot mix tuple and record projections");
                    }
                    // A single raw spread cannot change from tuple to record
                    // projection semantics halfway through its projections.
                    if ((slot.label() == null)
                            != (sourceOrder.get(i - 1).label() == null)) {
                        throw new IllegalArgumentException(
                                "checked call item mixes tuple and record projections");
                    }
                    if (slot.projectionIndex() < 0
                            || previousProjectionIndex < 0
                            || slot.projectionIndex()
                                    != previousProjectionIndex + 1) {
                        throw new IllegalArgumentException(
                                "checked call projection provenance must be contiguous and ordered");
                    }
                } else if (slot.projectionIndex() > 0) {
                    throw new IllegalArgumentException(
                            "checked call projection provenance must begin at projection 0");
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
                        "checked call source/ABI maps disagree");
            }
        }

        for (int parameterIndex = 0;
                parameterIndex < parameterNames.size();
                parameterIndex++) {
            int source = abiToSource.get(parameterIndex);
            if (source < -1 || source >= sourceOrder.size()) {
                throw new IllegalArgumentException(
                        "checked call plan contains invalid ABI source index");
            }
            if (source == -1) {
                if (seenTarget[parameterIndex]) {
                    throw new IllegalArgumentException(
                            "checked call omitted/bound slot bookkeeping disagrees");
                }
                if (!omittable.get(parameterIndex)) {
                    throw new IllegalArgumentException(
                            "checked call plan omits required formal slot "
                                    + parameterIndex);
                }
            } else {
                if (!seenTarget[parameterIndex]
                        || sourceOrder.get(source).parameterIndex()
                                != parameterIndex) {
                    throw new IllegalArgumentException(
                            "checked call ABI source does not point back to its formal slot");
                }
            }
        }
    }

    public static CheckedCallPlan required(
            List<String> parameterNames,
            List<InputSlot> inputs) {
        Objects.requireNonNull(parameterNames, "parameterNames");
        return bind(
                parameterNames,
                java.util.Collections.nCopies(
                        parameterNames.size(), Boolean.FALSE),
                inputs);
    }

    public static CheckedCallPlan bind(
            List<String> parameterNames,
            List<Boolean> omittable,
            List<InputSlot> inputs) {
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

        Map<String, Integer> parameterIndexByName = new HashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String name = Objects.requireNonNull(
                    parameterNames.get(i), "parameter name");
            if (name.isBlank()
                    || parameterIndexByName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException(
                        "duplicate or empty declared parameter name: " + name);
            }
            Objects.requireNonNull(
                    omittable.get(i), "omittable flag");
        }

        Integer[] abiToSource = new Integer[parameterNames.size()];
        Arrays.fill(abiToSource, -1);
        Set<Integer> claimedTargets = new HashSet<>();
        List<BoundSlot> bound = new ArrayList<>(inputs.size());
        boolean namedStarted = false;
        int nextPositional = 0;

        for (int sourceIndex = 0;
                sourceIndex < inputs.size();
                sourceIndex++) {
            InputSlot input =
                    Objects.requireNonNull(
                            inputs.get(sourceIndex), "input slot");
            int target;
            if (input.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument or record spread");
                }
                target = nextPositional++;
                if (target >= parameterNames.size()) {
                    throw new IllegalArgumentException(
                            "too many positional arguments");
                }
            } else {
                namedStarted = true;
                Integer declared =
                        parameterIndexByName.get(input.label());
                if (declared == null) {
                    throw new IllegalArgumentException(
                            "unknown named argument '" + input.label() + "'");
                }
                target = declared;
            }

            if (!claimedTargets.add(target)) {
                throw new IllegalArgumentException(
                        "duplicate binding for parameter '"
                                + parameterNames.get(target) + "'");
            }

            abiToSource[target] = sourceIndex;
            bound.add(new BoundSlot(
                    sourceIndex,
                    target,
                    input.label(),
                    input.callItemIndex(),
                    input.projectionIndex()));
        }

        for (int parameterIndex = 0;
                parameterIndex < parameterNames.size();
                parameterIndex++) {
            if (abiToSource[parameterIndex] == -1
                    && !omittable.get(parameterIndex)) {
                throw new IllegalArgumentException(
                        "missing required argument for parameter '"
                                + parameterNames.get(parameterIndex) + "'");
            }
        }

        return new CheckedCallPlan(
                parameterNames,
                omittable,
                bound,
                List.of(abiToSource));
    }

    public int suppliedCount() {
        return sourceOrder.size();
    }

    public int omittedCount() {
        int count = 0;
        for (int source : abiToSource) {
            if (source == -1) count++;
        }
        return count;
    }

    public boolean isOmitted(int parameterIndex) {
        return abiToSource.get(parameterIndex) == -1;
    }

    public int parameterForSource(int sourceIndex) {
        return sourceOrder.get(sourceIndex).parameterIndex();
    }
}
