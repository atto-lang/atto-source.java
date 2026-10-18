package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import java.util.HashSet;
import java.util.Set;

/** Explicit AST visitor for AOT-safe free-variable computation. */
final class LambdaCaptureExpressions {
    private LambdaCaptureExpressions() { }

    static void pattern(Ast.Pattern p, Set<String> locals) {
        if (p instanceof Ast.BindingPattern b) locals.add(b.name());
        else if (p instanceof Ast.TypePattern b && b.binding() != null) locals.add(b.binding());
        else if (p instanceof Ast.StructuralPattern b && b.binding() != null) locals.add(b.binding());
        else if (p instanceof Ast.TuplePattern b) for (Ast.Pattern x : b.elements()) pattern(x, locals);
        else if (p instanceof Ast.ConstructorPattern b) for (Ast.Pattern x : b.arguments()) pattern(x, locals);
        else if (!(p instanceof Ast.WildcardPattern || p instanceof Ast.LiteralPattern))
            throw new IllegalArgumentException("unknown capture pattern " + p);
    }

    static void condition(Ast.Expr p, Set<String> locals) {
        if (p instanceof Ast.TypeTestExpr t && t.binding() != null) locals.add(t.binding());
        else if (p instanceof Ast.PatternTestExpr t) pattern(t.pattern(), locals);
        else if (p instanceof Ast.BinaryExpr b && b.operator().equals("&&")) {
            condition(b.left(), locals);
            condition(b.right(), locals);
        }
    }

    static void expr(Ast.Expr p, Set<String> locals, Set<String> free) {
        if (p == null || p instanceof Ast.LiteralExpr) return;
        if (p instanceof Ast.NameExpr e) {
            if (!locals.contains(e.name())) free.add(e.name());
        } else if (p instanceof Ast.BinaryExpr e) {
            expr(e.left(), locals, free);
            Set<String> right = new HashSet<>(locals);
            if (e.operator().equals("&&")) condition(e.left(), right);
            expr(e.right(), right, free);
        } else if (p instanceof Ast.AssignExpr e) {
            expr(e.target(), locals, free); expr(e.value(), locals, free);
        } else if (p instanceof Ast.UnaryExpr e) expr(e.operand(), locals, free);
        else if (p instanceof Ast.ConditionalExpr e) {
            expr(e.condition(), locals, free);
            Set<String> yes = new HashSet<>(locals);
            condition(e.condition(), yes);
            expr(e.whenTrue(), yes, free);
            expr(e.whenFalse(), locals, free);
        } else if (p instanceof Ast.MatchExpr e) {
            expr(e.subject(), locals, free);
            for (Ast.MatchValueArm arm : e.arms()) {
                Set<String> inner = new HashSet<>(locals);
                pattern(arm.pattern(), inner);
                expr(arm.guard(), inner, free);
                expr(arm.value(), inner, free);
            }
        } else if (p instanceof Ast.TypeTestExpr e) expr(e.value(), locals, free);
        else if (p instanceof Ast.PatternTestExpr e) expr(e.value(), locals, free);
        else if (p instanceof Ast.CastExpr e) expr(e.value(), locals, free);
        else if (p instanceof Ast.SpreadExpr e) expr(e.expression(), locals, free);
        else if (p instanceof Ast.CallExpr e) {
            expr(e.callee(), locals, free);
            for (Ast.Expr x : e.arguments()) expr(x, locals, free);
        } else if (p instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr x : e.arguments()) expr(x, locals, free);
        } else if (p instanceof Ast.MemberExpr e) expr(e.receiver(), locals, free);
        else if (p instanceof Ast.IndexExpr e) {
            expr(e.receiver(), locals, free); expr(e.index(), locals, free);
        } else if (p instanceof Ast.NewExpr e) {
            for (Ast.Expr x : e.arguments()) expr(x, locals, free);
        } else if (p instanceof Ast.AwaitExpr e) expr(e.expression(), locals, free);
        else if (p instanceof Ast.TrapExpr e) expr(e.expression(), locals, free);
        else if (p instanceof Ast.ChannelOpExpr e) {
            expr(e.channel(), locals, free);
            expr(e.value(), locals, free);
            if (e.callback()) LambdaCapturePlanner.scan(e.callbackBody(), locals, free);
        } else if (p instanceof Ast.DynamicSelectExpr e) expr(e.cases(), locals, free);
        else if (p instanceof Ast.ListExpr e) {
            for (Ast.Expr x : e.elements()) expr(x, locals, free);
        } else if (p instanceof Ast.TupleExpr e) {
            for (Ast.Expr x : e.elements()) expr(x, locals, free);
        } else if (p instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField x : e.fields()) expr(x.value(), locals, free);
        } else if (p instanceof Ast.LambdaExpr e) {
            if (e.nonLexical()) return;
            Set<String> inner = new HashSet<>(locals);
            for (Ast.Param x : e.parameters()) inner.add(x.name());
            inner.add("arguments");
            if (e.expressionBody() != null) expr(e.expressionBody(), inner, free);
            else LambdaCapturePlanner.scan(e.blockBody(), inner, free);
        } else throw new IllegalArgumentException("unhandled lexical capture expression " + p);
    }
}
