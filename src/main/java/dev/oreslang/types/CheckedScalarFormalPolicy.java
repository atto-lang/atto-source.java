package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Restricted, immutable scalar/Option<int> callable admission contract.
 *
 * This is deliberately NOT a generic default-expression evaluator. Only
 * int constant defaults and typed None for Option<int> are admitted. Every
 * other optional signature must retain the global fail-closed ABI gate.
 */
public final class CheckedScalarFormalPolicy {
    private CheckedScalarFormalPolicy() { }

    public static boolean isClosedInt(Ast.TypeRef type) {
        return type != null && type.name().equals("int")
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    public static boolean isClosedIntOption(Ast.TypeRef type) {
        return type != null && type.name().equals("Option")
                && !type.inferArguments() && type.arguments().size() == 1
                && isClosedInt(type.arguments().getFirst());
    }

    public static boolean admissibleFormal(Ast.Param formal) {
        if (formal.mutable() || formal.structural()
                || !(isClosedInt(formal.type()) || isClosedIntOption(formal.type()))) {
            return false;
        }
        if (formal.defaultValue() != null && !formal.omittable()) return false;
        if (!formal.omittable()) return formal.defaultValue() == null;
        if (isClosedInt(formal.type())) {
            return formal.defaultValue() instanceof Ast.LiteralExpr literal
                    && literal.value() instanceof Long;
        }
        // Only None and implicit typed None have an admitted tagged Option ABI.
        // Some(payload) default evaluation needs separate ownership/effects work.
        return formal.defaultValue() == null
                || formal.defaultValue() instanceof Ast.NameExpr name
                        && name.name().equals("None");
    }

    public static boolean admitted(Ast.FunctionDecl fn) {
        if (fn.actorKind() != Ast.ActorKind.NONE
                || !fn.genericParameters().isEmpty()
                || fn.async() || fn.suspends() || fn.generator() || fn.trapped()
                || !fn.effects().isEmpty()
                || !isClosedInt(fn.returnType())) {
            return false;
        }
        return fn.parameters().stream().allMatch(CheckedScalarFormalPolicy::admissibleFormal);
    }

    public static boolean hasOmissions(Collection<Ast.FunctionDecl> family) {
        return family != null && family.stream()
                .anyMatch(fn -> fn.parameters().stream().anyMatch(Ast.Param::omittable));
    }

    public static CheckedCallPlan binding(Ast.FunctionDecl target, Ast.CallExpr call) {
        Objects.requireNonNull(target, "target function");
        Objects.requireNonNull(call, "source call");
        if (!admitted(target) || call.typeArgumentsPresent()
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.arguments().stream().anyMatch(Ast.SpreadExpr.class::isInstance)) {
            throw new IllegalArgumentException(
                    "SCALAR_OPTIONAL_ABI_UNSUPPORTED: checked scalar function/direct call required");
        }
        List<CheckedCallPlan.InputSlot> inputs = new ArrayList<>(call.arguments().size());
        for (int i = 0; i < call.arguments().size(); i++) {
            String label = call.argumentLabels().get(i);
            inputs.add(label.isEmpty()
                    ? CheckedCallPlan.InputSlot.positional(i)
                    : CheckedCallPlan.InputSlot.named(label, i));
        }
        return CheckedCallPlan.shapeForDeclaredParameters(target.parameters(), inputs);
    }

    /**
     * Resolve shape independently from optional-arity/name matching. Never
     * pick a candidate using labels when another overload is also viable.
     * Missing/duplicate formals are rejected without evaluating guest code.
     */
    public static Ast.FunctionDecl select(
            Collection<Ast.FunctionDecl> family, Ast.CallExpr call, String label) {
        if (family == null || family.isEmpty()) {
            throw new IllegalArgumentException("SCALAR_CALL_UNRESOLVED: " + label);
        }
        Ast.FunctionDecl match = null;
        for (Ast.FunctionDecl candidate : family) {
            if (!admitted(candidate)) continue;
            try {
                binding(candidate, call);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException(
                        "SCALAR_CALL_AMBIGUOUS: overlapping optional arities for " + label);
            }
            match = candidate;
        }
        if (match == null) {
            throw new IllegalArgumentException(
                    "SCALAR_CALL_INVALID: no checked scalar signature matches " + label);
        }
        return match;
    }

    /** Effective type-checker arguments in formal order; not an eval plan. */
    public static List<Ast.Expr> formalExpressions(
            Ast.FunctionDecl target, Ast.CallExpr call, CheckedCallPlan plan) {
        List<Ast.Expr> result = new ArrayList<>(target.parameters().size());
        for (int i = 0; i < target.parameters().size(); i++) {
            int source = plan.abiToSource().get(i);
            if (source >= 0) {
                result.add(call.arguments().get(source));
                continue;
            }
            Ast.Param formal = target.parameters().get(i);
            if (!formal.omittable()) {
                throw new IllegalStateException("required scalar formal has no source value");
            }
            Ast.Expr literal = formal.defaultValue();
            result.add(literal == null ? new Ast.NameExpr("None") : literal);
        }
        return List.copyOf(result);
    }
}
