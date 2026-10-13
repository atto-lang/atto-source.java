package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;

/**
 * Conservative, path-sensitive proof for local Result<void,E> obligations.
 *
 * This first slice deliberately recognizes only explicit match, propagation,
 * and checked extraction as discharge. Probes (is_ok/is_err), logging, and
 * mere mentions do not count. Unknown control-flow constructs fail closed
 * while the full compiler CFG/move proof is developed.
 */
final class ResultMustUseProof {
    private static final int PENDING = 1;
    private static final int HANDLED = 2;

    private ResultMustUseProof() { }

    static void requireHandled(List<Ast.Stmt> following, String variable) {
        int exits = walk(following, PENDING, variable);
        if ((exits & PENDING) != 0) {
            throw new IllegalArgumentException(
                    "must-use Result '" + variable
                            + "' is not handled or propagated on every path");
        }
    }

    private static int walk(List<Ast.Stmt> statements, int states, String variable) {
        for (Ast.Stmt statement : statements) {
            int next = 0;
            if ((states & PENDING) != 0) {
                next |= step(statement, PENDING, variable);
            }
            if ((states & HANDLED) != 0) {
                next |= step(statement, HANDLED, variable);
            }
            states = next;
            if (states == 0) break; // All paths returned.
        }
        return states;
    }

    private static int step(Ast.Stmt statement, int state, String variable) {
        if (state == PENDING && shadowsResult(statement, variable)) {
            throw new IllegalArgumentException(
                    "must-use Result '" + variable + "' is shadowed before handling");
        }
        if (statement instanceof Ast.ReturnStmt ret) {
            if (state == PENDING && !isVariable(ret.value(), variable)) {
                throw new IllegalArgumentException(
                        "must-use Result '" + variable + "' discarded by return");
            }
            return 0;
        }
        if (statement instanceof Ast.ExprStmt expr) {
            return state == PENDING && checkedExtraction(expr.expression(), variable)
                    ? HANDLED : state;
        }
        if (statement instanceof Ast.MatchStmt matched) {
            boolean consumes = state == PENDING && isVariable(matched.subject(), variable);
            // A wildcard-only match is a disguised discard of the Result.
            // Stage one requires explicit Ok and Err arms (and the ordinary
            // type checker independently proves all cases are exhaustive).
            if (consumes && !explicitResultArms(matched.arms())) {
                throw new IllegalArgumentException(
                        "must-use Result '" + variable
                                + "' requires explicit Ok and Err match arms");
            }
            int incoming = consumes ? HANDLED : state;
            int exits = 0;
            // The normal type checker first proves exhaustiveness and arm types.
            for (Ast.MatchArm arm : matched.arms()) {
                // A pattern binder can introduce a new local with the same
                // spelling as the pending Result. It cannot discharge the
                // outer Result merely because an inner variable is matched.
                if (!consumes && state == PENDING
                        && patternBinds(arm.pattern(), variable)) {
                    throw new IllegalArgumentException(
                            "must-use Result '" + variable
                                    + "' is shadowed by a match pattern");
                }
                exits |= walk(arm.body(), incoming, variable);
            }
            return exits;
        }
        if (statement instanceof Ast.IfStmt conditional) {
            int exits = 0;
            for (Ast.IfBranch branch : conditional.branches()) {
                exits |= walk(branch.body(), state, variable);
            }
            // Missing else is a possible fallthrough with the original state.
            exits |= walk(conditional.elseBody(), state, variable);
            return exits;
        }
        if (statement instanceof Ast.BlockStmt block) {
            return walk(block.body(), state, variable);
        }
        // Statements not yet modeled as a complete CFG can hide early exits.
        // They must not silently prove a pending Result safe.
        if (state == PENDING && !simpleFallthrough(statement)) {
            throw new IllegalArgumentException(
                    "must-use Result '" + variable
                            + "' crosses control flow without a handling proof");
        }
        return state;
    }

    private static boolean explicitResultArms(List<Ast.MatchArm> arms) {
        boolean ok = false;
        boolean err = false;
        for (Ast.MatchArm arm : arms) {
            if (arm.guard() != null
                    || !(arm.pattern() instanceof Ast.ConstructorPattern constructor)) {
                continue;
            }
            if (constructor.constructor().equals("Ok")) ok = true;
            if (constructor.constructor().equals("Err")) err = true;
        }
        return ok && err;
    }

    private static boolean shadowsResult(Ast.Stmt statement, String variable) {
        if (statement instanceof Ast.BindingStmt binding) {
            return binding.name().equals(variable);
        }
        if (statement instanceof Ast.DestructureStmt destructure) {
            return destructure.bindings().stream().anyMatch(
                    binding -> !binding.isDiscard() && binding.name().equals(variable));
        }
        return false;
    }

    private static boolean patternBinds(Ast.Pattern pattern, String variable) {
        if (pattern instanceof Ast.BindingPattern binding) {
            return binding.name().equals(variable);
        }
        if (pattern instanceof Ast.TypePattern typed) {
            return variable.equals(typed.binding());
        }
        if (pattern instanceof Ast.StructuralPattern structural) {
            return variable.equals(structural.binding());
        }
        if (pattern instanceof Ast.TuplePattern tuple) {
            return tuple.elements().stream().anyMatch(part -> patternBinds(part, variable));
        }
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            return constructor.arguments().stream().anyMatch(part -> patternBinds(part, variable));
        }
        return false;
    }

    private static boolean simpleFallthrough(Ast.Stmt statement) {
        return statement instanceof Ast.BindingStmt
                || statement instanceof Ast.DestructureStmt
                || statement instanceof Ast.DeferStmt;
    }

    private static boolean isVariable(Ast.Expr expression, String name) {
        return expression instanceof Ast.NameExpr identifier && identifier.name().equals(name);
    }

    private static boolean checkedExtraction(Ast.Expr expression, String name) {
        if (!(expression instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.MemberExpr member)
                || !isVariable(member.receiver(), name)) {
            return false;
        }
        return switch (member.member()) {
            case "unwrap", "expect", "unwrap_or" -> true;
            default -> false;
        };
    }
}
