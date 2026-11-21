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

    /**
     * One semantic-validation attempt, including nested linked/type-only
     * passes. A failing pass revokes only the call sites it visited, never
     * the plans of independent ASTs or concurrently checked programs.
     */
    public static final class ValidationScope implements AutoCloseable {
        private final ValidationScope parent;
        private final java.util.Set<Ast.Expr> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private boolean committed;
        private boolean closed;

        private ValidationScope(ValidationScope parent) {
            this.parent = parent;
        }

        public void commit() {
            if (closed || ACTIVE_VALIDATION.get() != this) {
                throw new IllegalStateException("checked-call validation scope is not active");
            }
            committed = true;
        }

        @Override public void close() {
            if (closed || ACTIVE_VALIDATION.get() != this) {
                throw new IllegalStateException("checked-call validation scopes must close in order");
            }
            closed = true;
            ACTIVE_VALIDATION.set(parent);
            if (!committed) {
                for (Ast.Expr site : visited) invalidate(site);
            }
        }
    }

    private static final ThreadLocal<ValidationScope> ACTIVE_VALIDATION =
            new ThreadLocal<>();

    public static ValidationScope beginValidation() {
        ValidationScope scope = new ValidationScope(ACTIVE_VALIDATION.get());
        ACTIVE_VALIDATION.set(scope);
        return scope;
    }

    /** Rechecking this exact AST must never use its older checked proof. */
    public static synchronized void beginRecheck(Ast.Expr callSite) {
        if (!(callSite instanceof Ast.CallExpr)
                && !(callSite instanceof Ast.NewExpr)) {
            throw new IllegalArgumentException(
                    "checked call recheck requires CallExpr or NewExpr");
        }
        for (ValidationScope scope = ACTIVE_VALIDATION.get();
                scope != null;
                scope = scope.parent) {
            scope.visited.add(callSite);
        }
        invalidate(callSite);
    }

    /**
     * Ownership validation uses already-published type proofs. Track each
     * consumed site without revoking the proof it must verify; a failed
     * ownership pass will revoke every site it actually inspected.
     */
    public static synchronized void trackValidationSite(Ast.Expr callSite) {
        Objects.requireNonNull(callSite, "checked call site");
        for (ValidationScope scope = ACTIVE_VALIDATION.get();
                scope != null;
                scope = scope.parent) {
            scope.visited.add(callSite);
        }
    }

    public static synchronized void invalidate(Ast.Expr callSite) {
        Objects.requireNonNull(callSite, "checked call site");
        expungeCheckedCallSites();
        CHECKED_CALL_SITES.remove(new IdentityKey(callSite, null));
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
            // A conflicting recheck must not leave the old proof executable.
            // Removing the identity-bound entry is atomic under this lock;
            // callers must revalidate before republishing any plan.
            CHECKED_CALL_SITES.remove(key);
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
        // This is identity-bound checked authority, not a reusable label map.
        // Retargeting even a structurally equal callee or replacing an
        // argument expression can alter lexical resolution, ownership,
        // effects, generic inference and source execution. Those rewrites
        // must run a fresh semantic/ownership check before execution.
        if (source instanceof Ast.CallExpr from
                && target instanceof Ast.CallExpr to) {
            if (from.origin() != to.origin()
                    || from.callee() != to.callee()
                    || from.typeArgumentsPresent() != to.typeArgumentsPresent()
                    || from.typeArguments().size() != to.typeArguments().size()) {
                return false;
            }
            for (int i = 0; i < from.typeArguments().size(); i++) {
                if (from.typeArguments().get(i) != to.typeArguments().get(i)) {
                    return false;
                }
            }
        } else if (source instanceof Ast.NewExpr from
                && target instanceof Ast.NewExpr to
                && from.type() != to.type()) {
            return false;
        }
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
            // Reusing precisely the already-checked source child is the only
            // proof-preserving case. Matching labels or AST equality is not a
            // substitute for checking a new expression's type and effects.
            if (sourceArguments.get(i) != targetArguments.get(i)) return false;
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

    /**
     * True when the plan is the trivial positional identity: no label, no
     * record projection, no omitted slot, and source order equals ABI order.
     * Such a plan carries no binding decision that the TypeChecker alone
     * could have made, so a consumer may reconstruct it when no plan was
     * published (standalone ownership analysis, unlabeled legacy paths).
     * Labeled, record-pack or omitted-slot plans must never be reconstructed.
     */
    public boolean isTriviallyPositional() {
        if (omittedCount() != 0) return false;
        for (BoundSlot slot : sourceOrder) {
            if (slot.label() != null) return false;
        }
        for (int i = 0; i < abiToSource.size(); i++) {
            if (abiToSource.get(i) != i) return false;
        }
        return true;
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
