package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit, fail-closed lexical statement visitor for local closures. */
final class LambdaCaptureStatements {
    private LambdaCaptureStatements() { }

    static void scan(List<Ast.Stmt> body, Set<String> parent, Set<String> free) {
        Set<String> locals = new HashSet<>(parent);
        for (Ast.Stmt stmt : body) walk(stmt, locals, free);
    }

    private static void value(Ast.Expr e, Set<String> locals, Set<String> free) {
        LambdaCaptureExpressions.expr(e, locals, free);
    }

    private static void walk(Ast.Stmt stmt, Set<String> locals, Set<String> free) {
        if (stmt instanceof Ast.BindingStmt s) {
            value(s.initializer(), locals, free);
            locals.add(s.name());
        } else if (stmt instanceof Ast.ReturnStmt s) {
            value(s.value(), locals, free);
        } else if (stmt instanceof Ast.ExprStmt s) {
            value(s.expression(), locals, free);
        } else if (stmt instanceof Ast.DestructureStmt s) {
            value(s.initializer(), locals, free);
            for (Ast.DestructureBinding b : s.bindings())
                if (!b.isDiscard()) locals.add(b.name());
        } else if (stmt instanceof Ast.YieldStmt s) {
            value(s.value(), locals, free);
        } else if (stmt instanceof Ast.DeferStmt s) {
            value(s.expression(), locals, free);
        } else if (stmt instanceof Ast.BlockStmt s) {
            scan(s.body(), locals, free);
        } else if (stmt instanceof Ast.LoopStmt s) {
            scan(s.body(), locals, free);
        } else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch arm : s.branches()) {
                value(arm.condition(), locals, free);
                Set<String> branch = new HashSet<>(locals);
                LambdaCaptureExpressions.condition(arm.condition(), branch);
                scan(arm.body(), branch, free);
            }
            scan(s.elseBody(), locals, free);
        } else if (stmt instanceof Ast.MatchStmt s) {
            value(s.subject(), locals, free);
            for (Ast.MatchArm arm : s.arms()) {
                Set<String> branch = new HashSet<>(locals);
                LambdaCaptureExpressions.pattern(arm.pattern(), branch);
                value(arm.guard(), branch, free);
                scan(arm.body(), branch, free);
            }
        } else if (stmt instanceof Ast.SwitchStmt s) {
            value(s.subject(), locals, free);
            for (Ast.SwitchCase arm : s.cases()) {
                for (Ast.Expr e : arm.constants()) value(e, locals, free);
                scan(arm.body(), locals, free);
            }
            scan(s.defaultBody(), locals, free);
        } else if (stmt instanceof Ast.TryStmt s) {
            scan(s.body(), locals, free);
            Set<String> caught = new HashSet<>(locals);
            if (s.errorName() != null) caught.add(s.errorName());
            scan(s.catchBody(), caught, free);
            scan(s.finallyBody(), locals, free);
        } else {
            throw new IllegalArgumentException("unsupported lexical capture statement " + stmt);
        }
    }
}
