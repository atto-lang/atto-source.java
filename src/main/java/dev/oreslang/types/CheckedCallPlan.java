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
        List<Integer> abiToSource,
        List<CheckedCallPlan.FormalParameter> formalParameters,
        List<CheckedCallPlan.OmittedSlot> omittedSlots) {

    public enum DefaultKind { REQUIRED, OPTION_NONE, CHECKED_CONSTANT, UNRESOLVED }
    public enum OmissionOrigin { IMPLICIT_ABSENCE, EXPLICIT_MARKER }
    public enum InputKind { VALUE, EXPLICIT_OMISSION }

    /** Declaration-side parameter metadata snapshotted into a checked call. */
    public record FormalParameter(
            String name,
            Ast.TypeRef type,
            boolean optionTypeSugar,
            boolean omittable,
            DefaultKind defaultKind,
            Ast.Expr defaultExpression) {
        public FormalParameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("formal parameter name cannot be blank");
            }
            Objects.requireNonNull(defaultKind, "formal default kind");
            if (optionTypeSugar
                    && (type == null || !type.name().equals("Option")
                            || type.inferArguments() || type.arguments().size() != 1)) {
                throw new IllegalArgumentException(
                        "type-position Option sugar requires exactly Option<T>");
            }
            if (!omittable) {
                if ((defaultKind != DefaultKind.REQUIRED
                        && defaultKind != DefaultKind.UNRESOLVED)
                        || defaultExpression != null) {
                    throw new IllegalArgumentException(
                            "required formal cannot carry an omission materializer");
                }
            } else if (defaultKind == DefaultKind.REQUIRED) {
                throw new IllegalArgumentException(
                        "omittable formal must declare a default materialization kind");
            }
            if (defaultKind == DefaultKind.CHECKED_CONSTANT && defaultExpression == null) {
                throw new IllegalArgumentException(
                        "checked-constant formal requires its exact default expression");
            }
            if ((defaultKind == DefaultKind.OPTION_NONE
                    || defaultKind == DefaultKind.UNRESOLVED)
                    && defaultExpression != null) {
                throw new IllegalArgumentException(
                        "None/unresolved formal cannot also carry a constant default expression");
            }
            if (defaultKind != DefaultKind.UNRESOLVED && type == null) {
                throw new IllegalArgumentException(
                        "checked formal metadata requires its declared type");
            }
        }

        public static FormalParameter from(
                Ast.Param parameter, DefaultKind checkedDefaultKind) {
            Objects.requireNonNull(parameter, "AST formal parameter");
            return new FormalParameter(
                    parameter.name(), parameter.type(), parameter.optionTypeSugar(),
                    parameter.omittable(), checkedDefaultKind, parameter.defaultValue());
        }
    }

    /** How an omitted ABI slot is materialized after successful checking. */
    public record OmittedSlot(
            int parameterIndex,
            OmissionOrigin origin,
            DefaultKind materialization,
            int callItemIndex) {
        public OmittedSlot {
            if (parameterIndex < 0) {
                throw new IllegalArgumentException("omitted formal index cannot be negative");
            }
            Objects.requireNonNull(origin, "omission origin");
            Objects.requireNonNull(materialization, "omission materialization");
            if (materialization == DefaultKind.REQUIRED) {
                throw new IllegalArgumentException("required formal cannot be omitted");
            }
            if (origin == OmissionOrigin.EXPLICIT_MARKER && callItemIndex < 0) {
                throw new IllegalArgumentException(
                        "explicit omission requires its source call-item index");
            }
            if (origin == OmissionOrigin.IMPLICIT_ABSENCE && callItemIndex != -1) {
                throw new IllegalArgumentException(
                        "implicit absence cannot claim a source call-item index");
            }
        }
    }

    /**
     * One raw call-list input. Explicit omission is not an evaluated source
     * expression, but still occupies a positional formal slot.
     */
    public record InputSlot(
            String label, int callItemIndex, int projectionIndex, InputKind kind) {
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
            Objects.requireNonNull(kind, "call input kind");
            if (kind == InputKind.EXPLICIT_OMISSION
                    && (label != null || callItemIndex < 0 || projectionIndex != -1)) {
                throw new IllegalArgumentException(
                        "explicit omission must be an unlabelled, provenanced positional item");
            }
        }

        public InputSlot(String label, int callItemIndex, int projectionIndex) {
            this(label, callItemIndex, projectionIndex, InputKind.VALUE);
        }
        public InputSlot(String label) {
            this(label, -1, -1, InputKind.VALUE);
        }
        public static InputSlot positional() { return new InputSlot(null); }
        public static InputSlot positional(int callItemIndex) {
            return new InputSlot(null, callItemIndex, -1);
        }
        public static InputSlot positionalProjection(int callItemIndex, int projectionIndex) {
            return new InputSlot(null, callItemIndex, projectionIndex);
        }
        public static InputSlot named(String label) {
            return new InputSlot(Objects.requireNonNull(label, "label"));
        }
        public static InputSlot named(String label, int callItemIndex) {
            return new InputSlot(Objects.requireNonNull(label, "label"), callItemIndex, -1);
        }
        public static InputSlot namedProjection(
                String label, int callItemIndex, int projectionIndex) {
            return new InputSlot(Objects.requireNonNull(label, "label"), callItemIndex, projectionIndex);
        }
        public static InputSlot explicitOmission(int callItemIndex) {
            return new InputSlot(null, callItemIndex, -1, InputKind.EXPLICIT_OMISSION);
        }
        public boolean isExplicitOmission() { return kind == InputKind.EXPLICIT_OMISSION; }
        public boolean hasProvenance() { return callItemIndex >= 0; }
    }

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

    /** One raw call-list item in lexical order. */
    public record BoundSlot(
            int sourceIndex,
            int parameterIndex,
            String label,
            int callItemIndex,
            int projectionIndex,
            boolean explicitOmission) {
        public BoundSlot(
                int sourceIndex, int parameterIndex, String label,
                int callItemIndex, int projectionIndex) {
            this(sourceIndex, parameterIndex, label, callItemIndex, projectionIndex, false);
        }
    }

    /** Compatibility constructor for the original pure binding-plan API. */
    public CheckedCallPlan(
            List<String> parameterNames,
            List<Boolean> omittable,
            List<BoundSlot> sourceOrder,
            List<Integer> abiToSource) {
        this(parameterNames, omittable, sourceOrder, abiToSource,
                legacyFormalParameters(parameterNames, omittable),
                legacyOmissions(omittable, abiToSource));
    }

    private static List<FormalParameter> legacyFormalParameters(
            List<String> parameterNames, List<Boolean> omittable) {
        Objects.requireNonNull(parameterNames, "parameterNames");
        Objects.requireNonNull(omittable, "omittable");
        if (parameterNames.size() != omittable.size()) {
            throw new IllegalArgumentException("parameter/defaultability metadata size mismatch");
        }
        List<FormalParameter> result = new ArrayList<>(parameterNames.size());
        for (int i = 0; i < parameterNames.size(); i++) {
            boolean mayOmit = Objects.requireNonNull(omittable.get(i), "omittable flag");
            result.add(new FormalParameter(
                    parameterNames.get(i), null, false, mayOmit,
                    DefaultKind.UNRESOLVED, null));
        }
        return List.copyOf(result);
    }

    private static List<OmittedSlot> legacyOmissions(
            List<Boolean> omittable, List<Integer> abiToSource) {
        Objects.requireNonNull(omittable, "omittable");
        Objects.requireNonNull(abiToSource, "abiToSource");
        if (omittable.size() != abiToSource.size()) {
            throw new IllegalArgumentException("checked call plan formal metadata size mismatch");
        }
        List<OmittedSlot> result = new ArrayList<>();
        for (int i = 0; i < abiToSource.size(); i++) {
            if (abiToSource.get(i) == -1) {
                if (!omittable.get(i)) {
                    throw new IllegalArgumentException("checked call plan omits a required formal");
                }
                result.add(new OmittedSlot(
                        i, OmissionOrigin.IMPLICIT_ABSENCE, DefaultKind.UNRESOLVED, -1));
            }
        }
        return List.copyOf(result);
    }

    public CheckedCallPlan {
        parameterNames = List.copyOf(parameterNames);
        omittable = List.copyOf(omittable);
        sourceOrder = List.copyOf(sourceOrder);
        abiToSource = List.copyOf(abiToSource);
        formalParameters = List.copyOf(formalParameters);
        omittedSlots = List.copyOf(omittedSlots);

        if (parameterNames.size() != omittable.size()
                || parameterNames.size() != abiToSource.size()
                || parameterNames.size() != formalParameters.size()) {
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
            Objects.requireNonNull(omittable.get(i), "omittable flag");
            FormalParameter formal = Objects.requireNonNull(
                    formalParameters.get(i), "formal parameter metadata");
            if (!formal.name().equals(name) || formal.omittable() != omittable.get(i)) {
                throw new IllegalArgumentException(
                        "formal parameter snapshot disagrees with plan name/omission bit");
            }
        }

        Map<Integer, OmittedSlot> omissionByFormal = new HashMap<>();
        for (OmittedSlot omitted : omittedSlots) {
            Objects.requireNonNull(omitted, "omitted slot");
            if (omitted.parameterIndex() >= parameterNames.size()
                    || omissionByFormal.putIfAbsent(omitted.parameterIndex(), omitted) != null) {
                throw new IllegalArgumentException("duplicate or out-of-range omitted formal slot");
            }
        }

        boolean[] seenTarget = new boolean[parameterNames.size()];
        boolean[] seenExplicitOmission = new boolean[parameterNames.size()];
        boolean namedStarted = false;
        int nextPositional = 0;
        int nextSourceIndex = 0;
        Set<String> seenLabels = new HashSet<>();
        Boolean provenanceMode = null;
        int previousCallItemIndex = -1;
        int previousProjectionIndex = -1;
        boolean previousProjectionWasNamed = false;
        boolean previousProjectionWasOmission = false;

        for (int i = 0; i < sourceOrder.size(); i++) {
            BoundSlot slot = Objects.requireNonNull(sourceOrder.get(i), "bound slot");
            if (slot.parameterIndex() < 0
                    || slot.parameterIndex() >= parameterNames.size()
                    || seenTarget[slot.parameterIndex()]) {
                throw new IllegalArgumentException(
                        "checked call plan has duplicate/out-of-range formal slot");
            }

            boolean provenanced = slot.callItemIndex() >= 0;
            if (slot.callItemIndex() < -1
                    || slot.projectionIndex() < -1
                    || (!provenanced && slot.projectionIndex() != -1)) {
                throw new IllegalArgumentException("invalid checked-call source provenance");
            }
            if (provenanced && slot.callItemIndex() < previousCallItemIndex) {
                throw new IllegalArgumentException(
                        "checked call provenance reorders source call items");
            }
            if (provenanceMode == null) {
                provenanceMode = provenanced;
            } else if (provenanceMode != provenanced) {
                throw new IllegalArgumentException(
                        "checked call plan cannot mix provenanced and unprovenanced inputs");
            }
            if (provenanced && slot.callItemIndex() == previousCallItemIndex) {
                if (slot.explicitOmission() || previousProjectionWasOmission
                        || slot.projectionIndex() < 0 || previousProjectionIndex < 0) {
                    throw new IllegalArgumentException(
                            "one call item cannot mix an omission marker with another input");
                }
                if ((slot.label() != null) != previousProjectionWasNamed) {
                    throw new IllegalArgumentException(
                            "one call item cannot mix tuple and record projections");
                }
                if (slot.projectionIndex() != previousProjectionIndex + 1) {
                    throw new IllegalArgumentException(
                            "checked call projection provenance must be contiguous and ordered");
                }
            } else if (provenanced && slot.projectionIndex() > 0) {
                throw new IllegalArgumentException(
                        "checked call projection provenance must begin at projection 0");
            }

            if (slot.explicitOmission()) {
                if (slot.sourceIndex() != -1 || slot.label() != null
                        || !provenanced || slot.projectionIndex() != -1) {
                    throw new IllegalArgumentException(
                            "explicit omission is not a source value and must retain a direct call-item index");
                }
                if (namedStarted) {
                    throw new IllegalArgumentException("positional omission follows a named argument");
                }
                if (!omittable.get(slot.parameterIndex())) {
                    throw new IllegalArgumentException(
                            "explicit omission targets required parameter '"
                                    + parameterNames.get(slot.parameterIndex()) + "'");
                }
                if (slot.parameterIndex() != nextPositional++) {
                    throw new IllegalArgumentException(
                            "explicit omission skips or reorders a positional formal slot");
                }
                seenTarget[slot.parameterIndex()] = true;
                seenExplicitOmission[slot.parameterIndex()] = true;
            } else {
                if (slot.sourceIndex() != nextSourceIndex++) {
                    throw new IllegalArgumentException(
                            "checked call plan does not preserve dense source-value order");
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
                    if (!parameterNames.get(slot.parameterIndex()).equals(slot.label())) {
                        throw new IllegalArgumentException(
                                "checked call label/formal mapping disagrees for '"
                                        + slot.label() + "'");
                    }
                }
                seenTarget[slot.parameterIndex()] = true;
                if (!Objects.equals(
                        abiToSource.get(slot.parameterIndex()), slot.sourceIndex())) {
                    throw new IllegalArgumentException("checked call source/ABI maps disagree");
                }
            }
            if (provenanced) {
                previousCallItemIndex = slot.callItemIndex();
                previousProjectionIndex = slot.projectionIndex();
                previousProjectionWasNamed = slot.label() != null;
                previousProjectionWasOmission = slot.explicitOmission();
            }
        }

        for (int parameterIndex = 0; parameterIndex < parameterNames.size(); parameterIndex++) {
            int source = abiToSource.get(parameterIndex);
            if (source < -1 || source >= nextSourceIndex) {
                throw new IllegalArgumentException("checked call plan contains invalid ABI source index");
            }
            OmittedSlot omission = omissionByFormal.get(parameterIndex);
            if (source == -1) {
                if (!omittable.get(parameterIndex) || omission == null) {
                    throw new IllegalArgumentException(
                            "checked call plan omits a required or unclassified formal slot "
                                    + parameterIndex);
                }
                if (omission.materialization() != formalParameters.get(parameterIndex).defaultKind()) {
                    throw new IllegalArgumentException(
                            "omitted slot materialization disagrees with formal default metadata");
                }
                if (omission.origin() == OmissionOrigin.EXPLICIT_MARKER) {
                    if (!seenExplicitOmission[parameterIndex]
                            || !seenTarget[parameterIndex] || omission.callItemIndex() < 0) {
                        throw new IllegalArgumentException(
                                "explicit omission provenance disagrees with raw call-item plan");
                    }
                    BoundSlot marker = null;
                    for (BoundSlot candidate : sourceOrder) {
                        if (candidate.explicitOmission()
                                && candidate.parameterIndex() == parameterIndex) {
                            if (marker != null) {
                                throw new IllegalArgumentException(
                                        "duplicate explicit omission marker for one formal slot");
                            }
                            marker = candidate;
                        }
                    }
                    if (marker == null) {
                        throw new IllegalArgumentException(
                                "explicit omission marker is missing from call-item provenance");
                    }
                    if (marker.callItemIndex() != omission.callItemIndex()) {
                        throw new IllegalArgumentException(
                                "explicit omission call-item provenance disagrees");
                    }
                } else if (seenTarget[parameterIndex] || seenExplicitOmission[parameterIndex]) {
                    throw new IllegalArgumentException(
                            "implicit absence cannot also claim an explicit call item");
                }
            } else {
                if (!seenTarget[parameterIndex] || omission != null
                        || seenExplicitOmission[parameterIndex]) {
                    throw new IllegalArgumentException(
                            "checked call ABI source/omission metadata disagrees");
                }
                BoundSlot supplied = sourceOrder.stream()
                        .filter(slot -> !slot.explicitOmission() && slot.sourceIndex() == source)
                        .findFirst().orElseThrow();
                if (supplied.parameterIndex() != parameterIndex) {
                    throw new IllegalArgumentException(
                            "checked call ABI source does not point back to its formal slot");
                }
            }
        }
        if (omissionByFormal.size() != omittedSlots.size()) {
            throw new IllegalArgumentException("duplicate omitted-slot metadata");
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
        if (parameterNames.size() != omittable.size()) {
            throw new IllegalArgumentException("parameter/defaultability metadata size mismatch");
        }
        List<FormalParameter> formals = new ArrayList<>(parameterNames.size());
        for (int i = 0; i < parameterNames.size(); i++) {
            boolean mayOmit = Objects.requireNonNull(omittable.get(i), "omittable flag");
            formals.add(new FormalParameter(
                    parameterNames.get(i), null, false, mayOmit,
                    DefaultKind.UNRESOLVED, null));
        }
        return bind(formals, inputs);
    }

    /** Bind raw items to fixed ABI slots without evaluating/reordering expressions. */
    public static CheckedCallPlan bind(
            List<FormalParameter> formalParameters, List<InputSlot> inputs) {
        Objects.requireNonNull(formalParameters, "formalParameters");
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() > formalParameters.size()) {
            throw new IllegalArgumentException(
                    "too many call items: expected at most "
                            + formalParameters.size() + ", got " + inputs.size());
        }

        List<String> parameterNames = formalParameters.stream().map(FormalParameter::name).toList();
        List<Boolean> omittable = formalParameters.stream().map(FormalParameter::omittable).toList();
        Map<String, Integer> parameterIndexByName = new HashMap<>();
        for (int i = 0; i < formalParameters.size(); i++) {
            FormalParameter formal = Objects.requireNonNull(formalParameters.get(i), "formal parameter");
            if (formal.name().isBlank()
                    || parameterIndexByName.putIfAbsent(formal.name(), i) != null) {
                throw new IllegalArgumentException(
                        "duplicate or empty declared parameter name: " + formal.name());
            }
        }

        Integer[] abiToSource = new Integer[formalParameters.size()];
        Arrays.fill(abiToSource, -1);
        Set<Integer> claimedTargets = new HashSet<>();
        List<BoundSlot> bound = new ArrayList<>(inputs.size());
        List<OmittedSlot> omissions = new ArrayList<>();
        boolean namedStarted = false;
        int nextPositional = 0;
        int nextSourceIndex = 0;

        for (InputSlot input : inputs) {
            Objects.requireNonNull(input, "input slot");
            int target;
            if (input.isExplicitOmission()) {
                if (namedStarted) {
                    throw new IllegalArgumentException("positional omission follows named argument");
                }
                target = nextPositional++;
                if (target >= formalParameters.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
                FormalParameter formal = formalParameters.get(target);
                if (!formal.omittable()) {
                    throw new IllegalArgumentException(
                            "cannot explicitly omit required parameter '" + formal.name() + "'");
                }
                if (!claimedTargets.add(target)) {
                    throw new IllegalArgumentException(
                            "duplicate binding for parameter '" + formal.name() + "'");
                }
                bound.add(new BoundSlot(
                        -1, target, null, input.callItemIndex(), -1, true));
                omissions.add(new OmittedSlot(
                        target, OmissionOrigin.EXPLICIT_MARKER,
                        formal.defaultKind(), input.callItemIndex()));
                continue;
            }

            if (input.label() == null) {
                if (namedStarted) {
                    throw new IllegalArgumentException(
                            "positional argument follows named argument or record spread");
                }
                target = nextPositional++;
                if (target >= formalParameters.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
            } else {
                namedStarted = true;
                Integer declared = parameterIndexByName.get(input.label());
                if (declared == null) {
                    throw new IllegalArgumentException("unknown named argument '" + input.label() + "'");
                }
                target = declared;
            }

            FormalParameter formal = formalParameters.get(target);
            if (!claimedTargets.add(target)) {
                throw new IllegalArgumentException(
                        "duplicate binding for parameter '" + formal.name() + "'");
            }
            bound.add(new BoundSlot(
                    nextSourceIndex, target, input.label(),
                    input.callItemIndex(), input.projectionIndex(), false));
            abiToSource[target] = nextSourceIndex++;
        }

        for (int parameterIndex = 0; parameterIndex < formalParameters.size(); parameterIndex++) {
            if (!claimedTargets.contains(parameterIndex)) {
                FormalParameter formal = formalParameters.get(parameterIndex);
                if (!formal.omittable()) {
                    throw new IllegalArgumentException(
                            "missing required argument for parameter '" + formal.name() + "'");
                }
                omissions.add(new OmittedSlot(
                        parameterIndex, OmissionOrigin.IMPLICIT_ABSENCE,
                        formal.defaultKind(), -1));
            }
        }

        return new CheckedCallPlan(
                parameterNames, omittable, bound, List.of(abiToSource), formalParameters, omissions);
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
        return (int) sourceOrder.stream().filter(slot -> !slot.explicitOmission()).count();
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
        return sourceOrder.stream()
                .filter(slot -> !slot.explicitOmission() && slot.sourceIndex() == sourceIndex)
                .findFirst()
                .orElseThrow(() -> new IndexOutOfBoundsException(
                        "no supplied source value at index " + sourceIndex))
                .parameterIndex();
    }
}
