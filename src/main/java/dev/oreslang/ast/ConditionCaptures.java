package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.List;

/** Admission for condition-local captures; no evaluation or expression hoisting. */
public final class ConditionCaptures {
    private ConditionCaptures() { }

    public static boolean contains(Ast.Expr expression) {
        if (expression == null || expression instanceof Ast.LambdaExpr) return false;
        if (expression instanceof Ast.CaptureExpr) return true;
        return children(expression).stream().anyMatch(ConditionCaptures::contains);
    }

    public static void validate(Ast.Expr expression) { validate(expression, true); }

    private static void validate(Ast.Expr expression, boolean unconditional) {
        if (expression == null || expression instanceof Ast.LambdaExpr) return;
        if (expression instanceof Ast.CaptureExpr && !unconditional) {
            throw new IllegalArgumentException(
                    "as let capture may be skipped by short-circuit or conditional evaluation; capture before that branch");
        }
        if (expression instanceof Ast.BinaryExpr binary
                && (binary.operator().equals("&&") || binary.operator().equals("||"))) {
            validate(binary.left(), unconditional);
            validate(binary.right(), false);
            return;
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            validate(conditional.condition(), unconditional);
            validate(conditional.whenTrue(), false);
            validate(conditional.whenFalse(), false);
            return;
        }
        if (expression instanceof Ast.MatchExpr matched) {
            validate(matched.subject(), unconditional);
            for (Ast.MatchValueArm arm : matched.arms()) {
                validate(arm.guard(), false);
                validate(arm.value(), false);
            }
            return;
        }
        for (Ast.Expr child : children(expression)) validate(child, unconditional);
    }

    private static List<Ast.Expr> children(Ast.Expr expression) {
        List<Ast.Expr> children = new ArrayList<>();
        if (expression instanceof Ast.CaptureExpr e) children.add(e.value());
        else if (expression instanceof Ast.BinaryExpr e) { children.add(e.left()); children.add(e.right()); }
        else if (expression instanceof Ast.UnaryExpr e) children.add(e.operand());
        else if (expression instanceof Ast.AssignExpr e) { children.add(e.target()); children.add(e.value()); }
        else if (expression instanceof Ast.ConditionalExpr e) {
            children.add(e.condition()); children.add(e.whenTrue()); children.add(e.whenFalse());
        } else if (expression instanceof Ast.MatchExpr e) {
            children.add(e.subject());
            for (Ast.MatchValueArm arm : e.arms()) {
                if (arm.guard() != null) children.add(arm.guard());
                children.add(arm.value());
            }
        } else if (expression instanceof Ast.TypeTestExpr e) children.add(e.value());
        else if (expression instanceof Ast.PatternTestExpr e) children.add(e.value());
        else if (expression instanceof Ast.CastExpr e) children.add(e.value());
        else if (expression instanceof Ast.SpreadExpr e) children.add(e.expression());
        else if (expression instanceof Ast.CallExpr e) { children.add(e.callee()); children.addAll(e.arguments()); }
        else if (expression instanceof Ast.RuntimeCallExpr e) children.addAll(e.arguments());
        else if (expression instanceof Ast.MemberExpr e) children.add(e.receiver());
        else if (expression instanceof Ast.IndexExpr e) { children.add(e.receiver()); children.add(e.index()); }
        else if (expression instanceof Ast.NewExpr e) children.addAll(e.arguments());
        else if (expression instanceof Ast.AwaitExpr e) children.add(e.expression());
        else if (expression instanceof Ast.TrapExpr e) children.add(e.expression());
        else if (expression instanceof Ast.ChannelOpExpr e) {
            children.add(e.channel()); if (e.value() != null) children.add(e.value());
        } else if (expression instanceof Ast.DynamicSelectExpr e) children.add(e.cases());
        else if (expression instanceof Ast.ListExpr e) children.addAll(e.elements());
        else if (expression instanceof Ast.TupleExpr e) children.addAll(e.elements());
        else if (expression instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) children.add(field.value());
        }
        return children;
    }
}
