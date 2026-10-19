package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Non-executable bridge between parsed v3 arguments and future typed ABI
 * lowering. This is not a runtime call, nominal Option resolver, or ownership
 * proof. In particular, no code should lower a preview directly to bytecode.
 *
 * Rebinds the original source against the declaration, preserving original
 * argument positions (including explicit omissions). Guest expressions are
 * returned in lexical order, without evaluating any of them.
 */
public final class V3CallLoweringPreview {
    private V3CallLoweringPreview() { }

    public enum SlotAction {
        /** Supplied value still requires exact type, ownership and effect checks. */
        CHECK_SUPPLIED,
        /** No guest expression: use the constant without Option wrapping. */
        DEFAULT_SCALAR,
        /** No guest expression: a resolved intrinsic Option<T> needs Some. */
        DEFAULT_SOME,
        /** No guest expression: explicit annotation's typed None. */
        DEFAULT_NONE,
        /** No default and no source item: intrinsic Option.None required. */
        OMITTED_OPTION_NONE
    }

    public record Slot(
            int formalIndex,
            String formalName,
            Ast.TypeRef unverifiedBodyType,
            SlotAction action,
            DefaultArgumentPolicy.Literal closedDefault,
            int evaluatedSourceIndex,
            int originalCallItemIndex,
            boolean requiresNominalOptionProof) {
        public Slot {
            if (formalIndex < 0 || formalName == null || formalName.isBlank()) {
                throw new IllegalArgumentException("invalid v3 formal slot");
            }
            Objects.requireNonNull(unverifiedBodyType, "preview type");
            Objects.requireNonNull(action, "slot action");
            if (evaluatedSourceIndex < -1 || originalCallItemIndex < -1) {
                throw new IllegalArgumentException("invalid source origin");
            }
            boolean usesDefault = switch (action) {
                case DEFAULT_SCALAR, DEFAULT_SOME, DEFAULT_NONE -> true;
                default -> false;
            };
            if (usesDefault != (closedDefault != null)) {
                throw new IllegalArgumentException("literal default provenance mismatch");
            }
            if (action == SlotAction.CHECK_SUPPLIED) {
                if (evaluatedSourceIndex < 0 || originalCallItemIndex < 0) {
                    throw new IllegalArgumentException("supplied call item has no provenance");
                }
            } else if (evaluatedSourceIndex != -1) {
                throw new IllegalArgumentException("omitted/defaulted slot has a guest expression");
            }
            if ((action == SlotAction.DEFAULT_SOME
                    || action == SlotAction.DEFAULT_NONE
                    || action == SlotAction.OMITTED_OPTION_NONE)
                    && !requiresNominalOptionProof) {
                throw new IllegalArgumentException("Option materialization needs nominal type proof");
            }
            if (action == SlotAction.DEFAULT_SCALAR
                    && (requiresNominalOptionProof
                        || closedDefault.kind() == DefaultArgumentPolicy.LiteralKind.NONE)) {
                throw new IllegalArgumentException("scalar default cannot materialize Option/None");
            }
            if (action == SlotAction.DEFAULT_SOME
                    && closedDefault.kind() == DefaultArgumentPolicy.LiteralKind.NONE) {
                throw new IllegalArgumentException("Some default requires a non-None literal");
            }
            if (action == SlotAction.DEFAULT_NONE
                    && closedDefault.kind() != DefaultArgumentPolicy.LiteralKind.NONE) {
                throw new IllegalArgumentException("typed None requires None literal");
            }
        }
    }

    public record Preview(List<Ast.Expr> unevaluatedExpressionsInSourceOrder, List<Slot> formalOrderSlots) {
        public Preview {
            unevaluatedExpressionsInSourceOrder = List.copyOf(unevaluatedExpressionsInSourceOrder);
            formalOrderSlots = List.copyOf(formalOrderSlots);
            boolean[] used = new boolean[unevaluatedExpressionsInSourceOrder.size()];
            int[] originalByDenseSource = new int[used.length];
            java.util.Set<Integer> usedRawCallItems = new HashSet<>();
            for (int i = 0; i < formalOrderSlots.size(); i++) {
                Slot slot = formalOrderSlots.get(i);
                if (slot.formalIndex() != i) {
                    throw new IllegalArgumentException("v3 preview slots must remain in formal order");
                }
                // Explicit omission items and supplied expressions are distinct
                // source occurrences. A forged preview must not reuse either.
                int raw = slot.originalCallItemIndex();
                if (raw >= 0 && !usedRawCallItems.add(raw)) {
                    throw new IllegalArgumentException(
                            "v3 preview has duplicated original call-item provenance");
                }
                int source = slot.evaluatedSourceIndex();
                if (source >= 0) {
                    if (source >= used.length || used[source]) {
                        throw new IllegalArgumentException(
                                "v3 preview has an out-of-range or duplicate guest expression");
                    }
                    used[source] = true;
                    originalByDenseSource[source] = raw;
                }
            }
            // Every parsed source call item is either a supplied expression
            // or an explicit omit. Missing raw indexes would allow a forged
            // Preview to hide an omitted argument, including its provenance.
            // Implicit defaults have no source item and retain raw index -1.
            int rawItems = usedRawCallItems.size();
            for (int raw = 0; raw < rawItems; raw++) {
                if (!usedRawCallItems.contains(raw)) {
                    throw new IllegalArgumentException(
                            "v3 preview has a gap in original call-item provenance");
                }
            }
            int previousCallItem = -1;
            for (int source = 0; source < used.length; source++) {
                if (!used[source]) {
                    throw new IllegalArgumentException("v3 preview lost a supplied guest expression");
                }
                int raw = originalByDenseSource[source];
                if (raw <= previousCallItem) {
                    throw new IllegalArgumentException(
                            "v3 preview would evaluate supplied expressions out of lexical order");
                }
                previousCallItem = raw;
            }
        }
    }

    /**
     * Validate against current declarations and the unchanged source AST; no
     * permission to execute or adapt an unresolved user-defined Option alias.
     */
    public static Preview plan(
            List<Ast.Param> parameters,
            List<Ast.Annotation> annotations,
            List<Ast.CallArgument> callItems,
            OptionalCallShapeBinder.Plan claimed) {
        Objects.requireNonNull(claimed, "claimed plan");
        List<V3DefaultArgValsPolicy.FormalPreview> signature =
                V3DefaultArgValsPolicy.validateDeclaration(parameters, annotations);
        List<OptionalCallShapeBinder.Parameter> formals =
                OptionalCallShapeBinder.stagedV3ParameterShapes(parameters, annotations);
        if (claimed.variadicParameterIndex() != -1) {
            throw new IllegalArgumentException("v3 rest formal ABI is not proved");
        }
        List<Ast.Expr> lexical = OptionalCallShapeBinder.evaluatedExpressionsInSourceOrder(
                formals, callItems, claimed);
        Map<Integer, Integer> explicitOrigins = new HashMap<>();
        for (OptionalCallShapeBinder.OmittedCallItem omission : claimed.omittedCallItemBindings()) {
            if (explicitOrigins.putIfAbsent(
                    omission.parameterIndex(), omission.callItemIndex()) != null) {
                throw new IllegalArgumentException("duplicate explicit omission origin");
            }
        }

        List<Slot> slots = new ArrayList<>(signature.size());
        for (int i = 0; i < signature.size(); i++) {
            V3DefaultArgValsPolicy.FormalPreview param = signature.get(i);
            int evaluatedIndex = claimed.sourceIndex(i);
            int originalIndex = evaluatedIndex == -1
                    ? explicitOrigins.getOrDefault(i, -1)
                    : claimed.callItemIndex(evaluatedIndex);
            DefaultArgumentPolicy.Literal defaultValue = null;
            SlotAction action;
            if (evaluatedIndex != -1) {
                action = SlotAction.CHECK_SUPPLIED;
            } else if (param.defaultValue() != null) {
                defaultValue = param.defaultValue();
                action = !param.optionTyped() ? SlotAction.DEFAULT_SCALAR
                        : defaultValue.kind() == DefaultArgumentPolicy.LiteralKind.NONE
                        ? SlotAction.DEFAULT_NONE : SlotAction.DEFAULT_SOME;
            } else if (param.optionTyped() && param.omittable()) {
                action = SlotAction.OMITTED_OPTION_NONE;
            } else {
                throw new IllegalArgumentException(
                        "required argument unexpectedly omitted: " + param.name());
            }
            slots.add(new Slot(i, param.name(), param.bodyType(), action,
                    defaultValue, evaluatedIndex, originalIndex, param.optionTyped()));
        }
        return new Preview(lexical, slots);
    }
}
