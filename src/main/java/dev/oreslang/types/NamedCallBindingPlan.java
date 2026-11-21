package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure exact-arity binder for required formals. It proves the source-to-formal
 * permutation for staged named calls without evaluating a single expression.
 *
 * <p>Optional/default/rest semantics remain in the separate staged binder work.
 * This class intentionally handles only the common required-parameter subset
 * needed before named calls can be lowered safely.</p>
 */
public final class NamedCallBindingPlan {
    private NamedCallBindingPlan() {}

    public record Plan(
            List<Integer> formalIndexBySourceItem,
            List<Integer> sourceItemIndexByFormal,
            int trailingClosureFormalIndex) {
        public Plan {
            formalIndexBySourceItem = List.copyOf(formalIndexBySourceItem);
            sourceItemIndexByFormal = List.copyOf(sourceItemIndexByFormal);
            if (formalIndexBySourceItem.size() != sourceItemIndexByFormal.size()) {
                throw new IllegalArgumentException(
                        "exact required call plan must map the same number of source items and formals");
            }
        }
    }

    public static Plan bindRequired(
            List<Ast.Param> formals,
            List<Ast.CallArgument> sourceItems) {
        Objects.requireNonNull(formals, "formals");
        Objects.requireNonNull(sourceItems, "sourceItems");

        Map<String, Integer> formalByName = new LinkedHashMap<>();
        for (int i = 0; i < formals.size(); i++) {
            Ast.Param formal = Objects.requireNonNull(formals.get(i), "formal");
            if (formal.name() == null || formal.name().isBlank()) {
                throw new IllegalArgumentException("formal parameter name cannot be blank");
            }
            if (formalByName.putIfAbsent(formal.name(), i) != null) {
                throw new IllegalArgumentException(
                        "duplicate formal parameter name '" + formal.name() + "'");
            }
        }

        boolean[] claimed = new boolean[formals.size()];
        ArrayList<Integer> formalIndexBySource = new ArrayList<>(sourceItems.size());
        boolean seenNamed = false;
        int nextPositional = 0;
        int trailingSourceIndex = -1;

        for (int sourceIndex = 0; sourceIndex < sourceItems.size(); sourceIndex++) {
            Ast.CallArgument item =
                    Objects.requireNonNull(sourceItems.get(sourceIndex), "call item");
            if (item.omitted()) {
                throw new IllegalArgumentException(
                        "required-only named-call binder does not admit omitted arguments");
            }
            if (item.spread()) {
                throw new IllegalArgumentException(
                        "required-only named-call binder does not admit spread arguments");
            }

            if (item.trailingClosure()) {
                if (trailingSourceIndex >= 0 || sourceIndex != sourceItems.size() - 1) {
                    throw new IllegalArgumentException(
                            "exactly one trailing closure may appear and it must be final");
                }
                if (!seenNamed) {
                    throw new IllegalArgumentException(
                            "trailing-closure exception applies only after named arguments");
                }
                trailingSourceIndex = sourceIndex;
                formalIndexBySource.add(-1);
                continue;
            }

            final int slot;
            if (item.label() != null) {
                seenNamed = true;
                Integer named = formalByName.get(item.label());
                if (named == null) {
                    throw new IllegalArgumentException(
                            "unknown named argument '" + item.label() + "'");
                }
                slot = named;
            } else {
                if (seenNamed) {
                    throw new IllegalArgumentException(
                            "ordinary positional argument after named argument");
                }
                while (nextPositional < claimed.length && claimed[nextPositional]) {
                    nextPositional++;
                }
                if (nextPositional >= formals.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
                slot = nextPositional++;
            }

            if (claimed[slot]) {
                throw new IllegalArgumentException(
                        "parameter '" + formals.get(slot).name() + "' was supplied more than once");
            }
            claimed[slot] = true;
            formalIndexBySource.add(slot);
        }

        int trailingFormal = -1;
        if (trailingSourceIndex >= 0) {
            ArrayList<Integer> remaining = new ArrayList<>();
            for (int i = 0; i < claimed.length; i++) {
                if (!claimed[i]) remaining.add(i);
            }
            if (remaining.size() != 1 || remaining.getFirst() != formals.size() - 1) {
                throw new IllegalArgumentException(
                        "trailing closure requires exactly the final formal to remain unclaimed");
            }
            trailingFormal = remaining.getFirst();
            Ast.Param callbackFormal = formals.get(trailingFormal);
            if (!isStaticallyKnownCallable(callbackFormal.type())) {
                throw new IllegalArgumentException(
                        "trailing closure requires a statically known Fnc final formal");
            }
            claimed[trailingFormal] = true;
            formalIndexBySource.set(trailingSourceIndex, trailingFormal);
        }

        for (int i = 0; i < claimed.length; i++) {
            if (!claimed[i]) {
                throw new IllegalArgumentException(
                        "missing required argument '" + formals.get(i).name() + "'");
            }
        }
        if (sourceItems.size() != formals.size()) {
            throw new IllegalArgumentException(
                    "exact required call needs one source item per formal");
        }

        Integer[] sourceByFormal = new Integer[formals.size()];
        Arrays.fill(sourceByFormal, -1);
        for (int sourceIndex = 0; sourceIndex < formalIndexBySource.size(); sourceIndex++) {
            int formalIndex = formalIndexBySource.get(sourceIndex);
            if (formalIndex < 0 || formalIndex >= sourceByFormal.length
                    || sourceByFormal[formalIndex] != -1) {
                throw new IllegalStateException("invalid or duplicate exact call-plan mapping");
            }
            sourceByFormal[formalIndex] = sourceIndex;
        }

        return new Plan(formalIndexBySource, List.of(sourceByFormal), trailingFormal);
    }

    public static void verifyAgainstSource(
            List<Ast.Param> formals,
            List<Ast.CallArgument> sourceItems,
            Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!bindRequired(formals, sourceItems).equals(plan)) {
            throw new IllegalArgumentException(
                    "named-call plan does not match the exact parsed source/formal snapshot");
        }
    }

    /**
     * Consumers evaluate these expressions in this returned lexical order,
     * storing temporaries before any formal-slot permutation.
     */
    public static List<Ast.Expr> evaluatedExpressionsInSourceOrder(
            List<Ast.Param> formals,
            List<Ast.CallArgument> sourceItems,
            Plan plan) {
        verifyAgainstSource(formals, sourceItems, plan);
        ArrayList<Ast.Expr> values = new ArrayList<>(sourceItems.size());
        for (Ast.CallArgument item : sourceItems) {
            if (item.value() == null || item.omitted() || item.spread()) {
                throw new IllegalArgumentException(
                        "exact required call item is not directly evaluable");
            }
            values.add(item.value());
        }
        return List.copyOf(values);
    }

    private static boolean isStaticallyKnownCallable(Ast.TypeRef type) {
        return type != null && type.name().equals("Fnc") && !type.arguments().isEmpty();
    }
}
