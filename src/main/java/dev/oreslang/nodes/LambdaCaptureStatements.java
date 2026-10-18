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
        } else {
            throw new IllegalArgumentException("unsupported lexical capture statement " + stmt);
        }
    }
}
