package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.Objects;

/**
 * The ONE structural contract for the initially executable guest scalar Task.
 * This is an AST shape check, not an ownership, type, actor-domain, or lifetime
 * proof. TypeChecker and source continuation lowering must both call it.
 *
 * <p>The only admitted body is a single explicit return of a literal or a
 * variable name, with no guest calls, field reads, borrows or side effects.
 * A value is copied out of the caller before the child is scheduled.
 */
public final class ScalarTaskPlan {
    private ScalarTaskPlan() { }

    public static Ast.Expr returnedScalar(Ast.TaskExpr task) {
        Objects.requireNonNull(task, "task");
        if (task.body().size() != 1
                || !(task.body().getFirst() instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.LiteralExpr
                    || returned.value() instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException(
                    "guest scalar task requires one return of a scalar literal or copied local name");
        }
        return returned.value();
    }
}
