package dev.oreslang.ast;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative syntactic upper bound on names reachable from a Task body.
 *
 * This is intentionally NOT an authorization or a substitute for the
 * ownership checker: it only controls which already-checked parent slots
 * are copied by the runtime at eager Task submission. A lexical-local name
 * may be included, which over-captures but can never drop an actual capture.
 * Nested deferred bodies are traversed transitively.
 *
 * Unknown future AST constructs fail closed rather than silently making
 * a Task resume with an omitted captured binding.
 */
public final class TaskCaptureNames {
    private TaskCaptureNames() { }

    public static Set<String> referencedIn(List<Ast.Stmt> body) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Ast.Stmt stmt : body) visitStmt(stmt, names);
        return Set.copyOf(names);
    }

    private static void visitBody(List<Ast.Stmt> body, Set<String> names) {
        for (Ast.Stmt stmt : body) visitStmt(stmt, names);
    }

    private static void visitStmt(Ast.Stmt stmt, Set<String> names) {
        if (stmt == null) return;
        if (stmt instanceof Ast.BindingStmt s) {
            visitExpr(s.initializer(), names);
        } else if (stmt instanceof Ast.DestructureStmt s) {
            visitExpr(s.initializer(), names);
        } else if (stmt instanceof Ast.ReturnStmt s) {
            visitExpr(s.value(), names);
        } else if (stmt instanceof Ast.YieldStmt s) {
            visitExpr(s.value(), names);
        } else if (stmt instanceof Ast.ExprStmt s) {
            visitExpr(s.expression(), names);
        } else if (stmt instanceof Ast.DeferStmt s) {
            visitExpr(s.expression(), names);
        } else if (stmt instanceof Ast.BlockStmt s) {
            visitBody(s.body(), names);
        } else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch branch : s.branches()) {
                visitExpr(branch.condition(), names);
                visitBody(branch.body(), names);
            }
            visitBody(s.elseBody(), names);
        } else if (stmt instanceof Ast.MatchStmt s) {
            visitExpr(s.subject(), names);
            for (Ast.MatchArm arm : s.arms()) {
                visitExpr(arm.guard(), names);
                visitBody(arm.body(), names);
            }
        } else if (stmt instanceof Ast.SwitchStmt s) {
            visitExpr(s.subject(), names);
            for (Ast.SwitchCase arm : s.cases()) {
                for (Ast.Expr value : arm.constants()) visitExpr(value, names);
                visitBody(arm.body(), names);
            }
            visitBody(s.defaultBody(), names);
        } else if (stmt instanceof Ast.TryStmt s) {
            visitBody(s.body(), names);
            visitBody(s.catchBody(), names);
            visitBody(s.finallyBody(), names);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            visitExpr(s.iterable(), names);
            visitBody(s.body(), names);
        } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
            visitExpr(s.iterable(), names);
            visitBody(s.body(), names);
        } else if (stmt instanceof Ast.ForStmt s) {
            visitStmt(s.initializer(), names);
            visitExpr(s.condition(), names);
            visitExpr(s.update(), names);
            visitBody(s.body(), names);
        } else if (stmt instanceof Ast.DoWhileStmt s) {
            visitBody(s.body(), names);
            visitExpr(s.condition(), names);
        } else if (stmt instanceof Ast.LoopStmt s) {
            visitBody(s.body(), names);
        } else if (stmt instanceof Ast.SelectStmt s) {
            for (Ast.SelectArm arm : s.arms()) {
                visitExpr(arm.channel(), names);
                visitExpr(arm.value(), names);
                visitBody(arm.body(), names);
            }
        } else if (!(stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt)) {
            throw new IllegalStateException("unsupported Task capture statement: "
                    + stmt.getClass().getName());
        }
    }

    private static void visitExpr(Ast.Expr expr, Set<String> names) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr s) {
            names.add(s.name());
        } else if (expr instanceof Ast.BinaryExpr s) {
            visitExpr(s.left(), names);
            visitExpr(s.right(), names);
        } else if (expr instanceof Ast.UnaryExpr s) {
            visitExpr(s.operand(), names);
        } else if (expr instanceof Ast.AssignExpr s) {
            visitExpr(s.target(), names);
            visitExpr(s.value(), names);
        } else if (expr instanceof Ast.ConditionalExpr s) {
            visitExpr(s.condition(), names);
            visitExpr(s.whenTrue(), names);
            visitExpr(s.whenFalse(), names);
        } else if (expr instanceof Ast.MatchExpr s) {
            visitExpr(s.subject(), names);
            for (Ast.MatchValueArm arm : s.arms()) {
                visitExpr(arm.guard(), names);
                visitExpr(arm.value(), names);
            }
        } else if (expr instanceof Ast.TypeTestExpr s) {
            visitExpr(s.value(), names);
        } else if (expr instanceof Ast.PatternTestExpr s) {
            visitExpr(s.value(), names);
        } else if (expr instanceof Ast.CastExpr s) {
            visitExpr(s.value(), names);
        } else if (expr instanceof Ast.SpreadExpr s) {
            visitExpr(s.expression(), names);
        } else if (expr instanceof Ast.CallExpr s) {
            visitExpr(s.callee(), names);
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, names);
        } else if (expr instanceof Ast.RuntimeCallExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, names);
        } else if (expr instanceof Ast.MemberExpr s) {
            visitExpr(s.receiver(), names);
        } else if (expr instanceof Ast.IndexExpr s) {
            visitExpr(s.receiver(), names);
            visitExpr(s.index(), names);
        } else if (expr instanceof Ast.NewExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, names);
        } else if (expr instanceof Ast.AwaitExpr s) {
            visitExpr(s.expression(), names);
        } else if (expr instanceof Ast.TaskExpr s) {
            visitBody(s.body(), names);
        } else if (expr instanceof Ast.TrapExpr s) {
            visitExpr(s.expression(), names);
        } else if (expr instanceof Ast.ChannelOpExpr s) {
            visitExpr(s.channel(), names);
            visitExpr(s.value(), names);
            visitBody(s.callbackBody(), names);
        } else if (expr instanceof Ast.DynamicSelectExpr s) {
            visitExpr(s.cases(), names);
        } else if (expr instanceof Ast.ListExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, names);
        } else if (expr instanceof Ast.TupleExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, names);
        } else if (expr instanceof Ast.ObjectExpr s) {
            for (Ast.ObjectField field : s.fields()) visitExpr(field.value(), names);
        } else if (expr instanceof Ast.LambdaExpr s) {
            // Even nested closures may capture outer Task values later.
            if (!s.nonLexical()) {
                visitExpr(s.expressionBody(), names);
                if (s.blockBody() != null) visitBody(s.blockBody(), names);
            }
        } else {
            throw new IllegalStateException("unsupported Task capture expression: "
                    + expr.getClass().getName());
        }
    }
}
