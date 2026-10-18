package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Computes lexical free variables; this is not Sendable/actor admission proof. */
public final class LambdaCapturePlanner {
    private LambdaCapturePlanner() { }

    public static Set<String> captures(Ast.LambdaExpr lambda) {
        if (lambda.nonLexical()) return Set.of();
        Set<String> locals = new HashSet<>();
        for (Ast.Param p : lambda.parameters()) locals.add(p.name());
        locals.add("arguments");
        Set<String> free = new LinkedHashSet<>();
        if (lambda.expressionBody() != null)
            LambdaCaptureExpressions.expr(lambda.expressionBody(), locals, free);
        else scan(lambda.blockBody(), locals, free);
        return Set.copyOf(free);
    }

    static void scan(List<Ast.Stmt> body, Set<String> outer, Set<String> free) {
        LambdaCaptureStatements.scan(body, outer, free);
    }
}
