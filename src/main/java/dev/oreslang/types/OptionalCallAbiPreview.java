package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Type-exact, non-executable ABI planning for staged named and T? calls.
 *
 * <p>This is an IR design/proof helper, not authorization to lower guest calls.
 * In particular, callers must independently resolve lexical owner, visibility,
 * generics, nominal subtyping, loan/move effects and the exact expression types.
 * Neither the type checker nor either backend consumes this preview yet.
 */
public final class OptionalCallAbiPreview {
    private OptionalCallAbiPreview() { }

    public enum Action {
        /** A required formal receives its already-checked value unmodified. */
        PASS_REQUIRED,
        /** A supplied T receives exactly one typed Some<T> wrapping. */
        WRAP_SOME,
        /** A supplied, already-typed Option<T> passes through unchanged. */
        PASS_OPTION,
        /** An absent T? formal receives compiler-generated typed None<T>. */
        INJECT_NONE
    }

    /**
     * One ABI slot in declaration order. -1 means there was no supplied
     * evaluated source expression; originalCallItemIndex may still identify
     * an explicit '_' or 'omit' item. No expression AST is duplicated here.
     */
    public record Slot(int formalIndex, String formalName, Ast.TypeRef calleeType,
                       Action action, int evaluatedSourceIndex, int originalCallItemIndex) {
        public Slot {
            if (formalIndex < 0 || formalName == null || formalName.isBlank()) {
                throw new IllegalArgumentException("invalid formal ABI slot");
            }
            requireExactConcreteType(calleeType);
            Objects.requireNonNull(action, "action");
            if (evaluatedSourceIndex < -1 || originalCallItemIndex < -1) {
                throw new IllegalArgumentException("negative ABI source origin");
            }
            if ((action == Action.INJECT_NONE) != (evaluatedSourceIndex == -1)) {
                throw new IllegalArgumentException("None injection and source index mismatch");
            }
            if (evaluatedSourceIndex >= 0 && originalCallItemIndex < 0) {
                throw new IllegalArgumentException("supplied value requires original call item");
            }
            if (action == Action.INJECT_NONE || action == Action.WRAP_SOME
                    || action == Action.PASS_OPTION) {
                if (!calleeType.name().equals("Option")
                        || calleeType.arguments().size() != 1
                        || calleeType.inferArguments()) {
                    throw new IllegalArgumentException(
                            "optional ABI action requires exactly Option<T> callee type");
                }
            }
        }
    }

    /**
     * Source expressions are retained once, in lexical order. The slot table
     * only references these indexes; its order is the full declared ABI arity.
     */
    public record Preview(List<Ast.Expr> sourceExpressions, List<Slot> abiSlots) {
        public Preview {
            sourceExpressions = List.copyOf(sourceExpressions);
            abiSlots = List.copyOf(abiSlots);
            boolean[] seenSource = new boolean[sourceExpressions.size()];
            java.util.Set<Integer> seenCallItems = new java.util.HashSet<>();
            for (int i = 0; i < abiSlots.size(); i++) {
                Slot slot = abiSlots.get(i);
                if (slot.formalIndex() != i) {
                    throw new IllegalArgumentException("ABI slots must retain declaration order");
                }
                int source = slot.evaluatedSourceIndex();
                if (source >= sourceExpressions.size()) {
                    throw new IllegalArgumentException("ABI slot references missing expression");
                }
                if (source >= 0) {
                    if (seenSource[source]) {
                        throw new IllegalArgumentException("ABI plan duplicates source evaluation");
                    }
                    seenSource[source] = true;
                }
                if (slot.originalCallItemIndex() >= 0
                        && !seenCallItems.add(slot.originalCallItemIndex())) {
                    throw new IllegalArgumentException("ABI plan reuses a call-item origin");
                }
            }
            for (boolean seen : seenSource) {
                if (!seen) {
                    throw new IllegalArgumentException("ABI plan drops a source expression");
                }
            }
            // Every explicit omission is a raw call item, whereas implicit
            // omission has no raw origin. Reconstructed previews must never
            // invent gaps in the parsed call list or reverse lexical effects.
            for (int item = 0; item < seenCallItems.size(); item++) {
                if (!seenCallItems.contains(item)) {
                    throw new IllegalArgumentException(
                            "ABI preview has a missing original call-item origin");
                }
            }
            int[] rawIndexBySource = new int[sourceExpressions.size()];
            for (Slot slot : abiSlots) {
                if (slot.evaluatedSourceIndex() >= 0) {
                    rawIndexBySource[slot.evaluatedSourceIndex()] =
                            slot.originalCallItemIndex();
                }
            }
            for (int i = 1; i < rawIndexBySource.length; i++) {
                if (rawIndexBySource[i] <= rawIndexBySource[i - 1]) {
                    throw new IllegalArgumentException(
                            "ABI preview reorders lexical source evaluations");
                }
            }
        }
        public int fullArity() { return abiSlots.size(); }
    }

    /**
     * Build a narrow *preview* for exact, independently resolved argument
     * types. No aliases, subtyping, generic instantiation, value coercions,
     * inferred None type or borrow loan proof are performed here. Unsupported
     * supplied types fail closed; type-only previews must never be executed.
     *
     * @param evaluatedTypes one independently checked, exact TypeRef per
     *                       evaluated expression in lexical order
     */
    public static Preview prepareExact(
            List<Ast.Param> selectedFormals,
            List<Ast.CallArgument> sourceItems,
            OptionalCallShapeBinder.Plan selectedPlan,
            List<Ast.TypeRef> evaluatedTypes) {
        Objects.requireNonNull(selectedFormals, "selectedFormals");
        Objects.requireNonNull(sourceItems, "sourceItems");
        Objects.requireNonNull(selectedPlan, "selectedPlan");
        Objects.requireNonNull(evaluatedTypes, "evaluatedTypes");
        List<OptionalCallShapeBinder.Parameter> shapes =
                OptionalCallShapeBinder.declaredParameterShapes(selectedFormals);
        OptionalCallShapeBinder.verifyPlanAgainstSource(shapes, sourceItems, selectedPlan);
        // Do not silently claim that rest packs have been lowered.
        if (selectedPlan.variadicParameterIndex() != -1) {
            throw new IllegalArgumentException("variadic ABI pack lowering is not proven");
        }

        List<Ast.Expr> expressions =
                OptionalCallShapeBinder.evaluatedExpressionsInSourceOrder(
                        shapes, sourceItems, selectedPlan);
        if (evaluatedTypes.size() != expressions.size()) {
            throw new IllegalArgumentException(
                    "one checked type is required per evaluated source expression");
        }
        for (Ast.TypeRef supplied : evaluatedTypes) {
            requireExactConcreteType(supplied);
        }

        // Scalar literal ASTs expose an independently knowable nominal type.
        // Do not accept a caller-provided type assertion that contradicts
        // the literal (including null). Other expression types still require
        // independent semantic checking and are NOT authenticated here.
        for (int source = 0; source < expressions.size(); source++) {
            OptionalCallLiteralStage.requireMatchingLiteralType(
                    expressions.get(source), evaluatedTypes.get(source));
        }

        Map<Integer, Integer> explicitOrigins = new HashMap<>();
        for (OptionalCallShapeBinder.OmittedCallItem omitted :
                selectedPlan.omittedCallItemBindings()) {
            if (explicitOrigins.putIfAbsent(
                    omitted.parameterIndex(), omitted.callItemIndex()) != null) {
                throw new IllegalArgumentException("duplicate explicit omission provenance");
            }
        }

        List<Slot> slots = new ArrayList<>(selectedFormals.size());
        for (int i = 0; i < selectedFormals.size(); i++) {
            Ast.Param formal = Objects.requireNonNull(selectedFormals.get(i), "formal");
            if (formal.argumentOmittable() || formal.type().containsStagedV3Option()) {
                throw new IllegalArgumentException(
                        "staged v3 type/presence cannot use legacy Option-only ABI preview");
            }
            int sourceIndex = selectedPlan.sourceIndex(i);
            Ast.TypeRef abiType = formal.calleeType();
            requireExactConcreteType(abiType);
            Action action;
            int originalItem;
            if (sourceIndex == -1) {
                if (!formal.optional()) {
                    throw new IllegalArgumentException("cannot inject None for required formal");
                }
                action = Action.INJECT_NONE;
                originalItem = explicitOrigins.getOrDefault(i, -1);
            } else {
                originalItem = selectedPlan.callItemIndex(sourceIndex);
                Ast.TypeRef supplied = evaluatedTypes.get(sourceIndex);
                if (formal.optional()) {
                    if (supplied.equals(formal.valueType())) {
                        action = Action.WRAP_SOME;
                    } else if (supplied.equals(abiType)) {
                        action = Action.PASS_OPTION;
                    } else {
                        throw new IllegalArgumentException(
                                "supplied optional value has no proven exact T/Option<T> type: "
                                        + formal.name());
                    }
                } else if (supplied.equals(abiType)) {
                    action = Action.PASS_REQUIRED;
                } else {
                    throw new IllegalArgumentException(
                            "supplied required value lacks exact type proof: " + formal.name());
                }
            }
            slots.add(new Slot(i, formal.name(), abiType, action, sourceIndex, originalItem));
        }
        return new Preview(expressions, slots);
    }


    /**
     * Non-executable call-target ordering. Ordinary calls evaluate the callee
     * (including a method receiver) before any supplied argument. Constructors
     * and actor spawns have no guest callee expression to evaluate. The
     * compiler must still prove the selected owner, generics, effects and
     * ownership before translating this recipe into executable IR.
     */
    public enum InvocationKind { CALL, CLASS_CONSTRUCTOR, ACTOR_SPAWN }

    /**
     * Factory-owned immutable recipe: target expression first when present,
     * then supplied argument expressions in their original lexical order.
     * Formal/ABI reordering occurs only through the separate slot table.
     * This is not a runtime instruction stream or authorization to execute.
     */
    public static final class InvocationPreview {
        private final InvocationKind kind;
        private final Ast.Expr calleeExpression;
        private final Ast.TypeRef constructedType;
        private final Preview arguments;
        private final List<Ast.Expr> evaluationOrder;

        private InvocationPreview(InvocationKind kind, Ast.Expr calleeExpression,
                                  Ast.TypeRef constructedType, Preview arguments) {
            this.kind = Objects.requireNonNull(kind, "invocation kind");
            this.arguments = Objects.requireNonNull(arguments, "argument preview");
            if (kind == InvocationKind.CALL) {
                this.calleeExpression = Objects.requireNonNull(calleeExpression, "call callee");
                if (constructedType != null) {
                    throw new IllegalArgumentException("ordinary call cannot have constructor type");
                }
                this.constructedType = null;
            } else {
                if (calleeExpression != null) {
                    throw new IllegalArgumentException("constructor/actor spawn cannot have a call callee");
                }
                this.calleeExpression = null;
                this.constructedType = Objects.requireNonNull(constructedType, "constructed type");
            }
            List<Ast.Expr> ordered = new ArrayList<>(
                    arguments.sourceExpressions().size() + (calleeExpression == null ? 0 : 1));
            if (calleeExpression != null) ordered.add(calleeExpression);
            ordered.addAll(arguments.sourceExpressions());
            this.evaluationOrder = List.copyOf(ordered);
        }

        public InvocationKind kind() { return kind; }
        /** Nullable only for class construction or actor spawning. */
        public Ast.Expr calleeExpression() { return calleeExpression; }
        /** Nullable only for an ordinary call. */
        public Ast.TypeRef constructedType() { return constructedType; }
        public Preview arguments() { return arguments; }
        public List<Ast.Expr> evaluationOrder() { return evaluationOrder; }
    }

    /**
     * Bind a staged ordinary call directly from its AST, not an independently
     * supplied source-item list. Explicit generic application is gated here:
     * substituted type parameters have not been proven by this preview.
     */
    public static InvocationPreview prepareStagedCallExact(
            Ast.StagedCallExpr call, List<Ast.Param> selectedFormals,
            List<Ast.TypeRef> checkedSourceTypes) {
        Objects.requireNonNull(call, "staged call");
        if (call.typeArgumentsPresent()) {
            throw new IllegalArgumentException(
                    "explicit generic call instantiation requires separate type substitution proof");
        }
        List<OptionalCallShapeBinder.Parameter> shapes =
                OptionalCallShapeBinder.declaredParameterShapes(selectedFormals);
        OptionalCallShapeBinder.Plan plan =
                OptionalCallShapeBinder.bindParsed(shapes, call.arguments());
        Preview arguments = prepareExact(
                selectedFormals, call.arguments(), plan, checkedSourceTypes);
        return new InvocationPreview(InvocationKind.CALL, call.callee(), null, arguments);
    }

    /**
     * Class constructors and actor spawns share fixed-slot source ordering,
     * but preserve distinct target kinds for future ownership/capability
     * checks. Generic constructor instantiation and rest packs remain gated.
     */
    public static InvocationPreview prepareStagedConstructionExact(
            Ast.StagedNewExpr construction, List<Ast.Param> selectedFormals,
            List<Ast.TypeRef> checkedSourceTypes) {
        Objects.requireNonNull(construction, "staged construction");
        Ast.TypeRef type = construction.type();
        requireExactConcreteType(type);
        if (type.inferArguments() || !type.arguments().isEmpty() || type.isBorrow()) {
            throw new IllegalArgumentException(
                    "generic or borrowed construction requires separate target type proof");
        }
        // Constructor 'self' is a construction-scoped receiver, not a guest
        // value argument. The caller must erase it *before* ABI planning:
        // silently binding it would corrupt effective arity and could make a
        // named 'self:' item appear to initialize or expose the receiver.
        for (Ast.Param formal : selectedFormals) {
            Objects.requireNonNull(formal, "construction formal");
            if (formal.name().equals("self")) {
                throw new IllegalArgumentException(
                        "construction-scoped self must be erased before user argument ABI binding");
            }
        }
        List<OptionalCallShapeBinder.Parameter> shapes =
                OptionalCallShapeBinder.formalParameterShapes(selectedFormals);
        OptionalCallShapeBinder.Plan plan =
                OptionalCallShapeBinder.bindParsed(shapes, construction.arguments());
        Preview arguments = prepareExact(
                selectedFormals, construction.arguments(), plan, checkedSourceTypes);
        return new InvocationPreview(
                construction.actorSpawn() ? InvocationKind.ACTOR_SPAWN
                                          : InvocationKind.CLASS_CONSTRUCTOR,
                null, type, arguments);
    }

    private static void requireExactConcreteType(Ast.TypeRef type) {
        Objects.requireNonNull(type, "resolved type");
        if (type.inferArguments() || type.name().isBlank()
                || type.name().startsWith("$") || type.name().equals("void")
                || type.name().equals("null") || type.name().equals("Unknown")
                || type.isBorrow()) {
            throw new IllegalArgumentException("unresolved, borrowed or non-value ABI type");
        }
        // The canonical Option family is parameterized by exactly one
        // concrete payload. A raw Option or an Option<T, U> must not acquire
        // an apparent "exact" ABI merely by matching a forged input type.
        if (type.name().equals("Option") && type.arguments().size() != 1) {
            throw new IllegalArgumentException("Option ABI type requires exactly one concrete type argument");
        }
        for (Ast.TypeRef argument : type.arguments()) {
            requireExactConcreteType(argument);
        }
    }
}
