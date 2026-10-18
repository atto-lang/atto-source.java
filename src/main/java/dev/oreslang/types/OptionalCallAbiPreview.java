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
            Objects.requireNonNull(calleeType, "calleeType");
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
            for (int i = 0; i < abiSlots.size(); i++) {
                Slot slot = abiSlots.get(i);
                if (slot.formalIndex() != i) {
                    throw new IllegalArgumentException("ABI slots must retain declaration order");
                }
                if (slot.evaluatedSourceIndex() >= sourceExpressions.size()) {
                    throw new IllegalArgumentException("ABI slot references missing expression");
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

    private static void requireExactConcreteType(Ast.TypeRef type) {
        Objects.requireNonNull(type, "resolved type");
        if (type.inferArguments() || type.name().isBlank()
                || type.name().startsWith("$") || type.name().equals("void")
                || type.name().equals("null") || type.name().equals("Unknown")
                || type.isBorrow()) {
            throw new IllegalArgumentException("unresolved, borrowed or non-value ABI type");
        }
        for (Ast.TypeRef argument : type.arguments()) {
            requireExactConcreteType(argument);
        }
    }
}
