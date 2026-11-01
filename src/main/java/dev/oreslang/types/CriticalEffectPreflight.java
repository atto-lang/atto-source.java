package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;

/**
 * A conservative source-AST critical-body admission preflight.
 *
 * No callable, method, FFI or operator with unknown effects is approved.
 * Until typed transitive effect closure can prove callees non-suspending,
 * all calls and unclassified syntax are rejected. This check CANNOT grant
 * permission to execute a critical node: compiler/evaluator lowering remains
 * independently fail-closed until scheduler tickets and lexical unwind exist.
 */
public final class CriticalEffectPreflight {
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = 4096;
    private int inspected;

    private CriticalEffectPreflight() { }

    public static void verify(List<Ast.Stmt> body) {
        new CriticalEffectPreflight().statements(body, 0);
    }

    private void budget(int depth) {
        if (depth > MAX_DEPTH || ++inspected > MAX_NODES)
            throw new IllegalArgumentException(
                    "critical body exceeds bounded static effect-check budget");
    }

    private void fail(String effect) {
        throw new IllegalArgumentException(
                "critical body cannot suspend or invoke unverified effects: " + effect);
    }

    private void statements(List<Ast.Stmt> statements, int depth) {
        budget(depth);
        for (Ast.Stmt statement : statements) statement(statement, depth + 1);
    }

    private void statement(Ast.Stmt stmt, int depth) {
        budget(depth);
        if (stmt instanceof Ast.BindingStmt binding) {
            expression(binding.initializer(), depth + 1);
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            expression(expression.expression(), depth + 1);
            return;
        }
        if (stmt instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) expression(returned.value(), depth + 1);
            return;
        }
        if (stmt instanceof Ast.BlockStmt block) {
            statements(block.body(), depth + 1);
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                expression(branch.condition(), depth + 1);
                statements(branch.body(), depth + 1);
            }
            statements(conditional.elseBody(), depth + 1);
            return;
        }
        // Nested critical may deadlock the same resource; try-critical also
        // needs explicit guard nesting and fallback ownership proofs.
        fail(stmt.getClass().getSimpleName());
    }

    private void expression(Ast.Expr expr, int depth) {
        budget(depth);
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;
        if (expr instanceof Ast.BinaryExpr binary) {
            expression(binary.left(), depth + 1);
            expression(binary.right(), depth + 1);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            expression(unary.operand(), depth + 1);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            // Only an already-authorized lexical variable may be assigned.
            // Property/index setters may dispatch user code and must wait for
            // independent member/call-effect and ownership proof.
            if (!(assignment.target() instanceof Ast.NameExpr)) {
                fail("dynamic assignment target");
            }
            expression(assignment.value(), depth + 1);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            expression(conditional.condition(), depth + 1);
            expression(conditional.whenTrue(), depth + 1);
            expression(conditional.whenFalse(), depth + 1);
            return;
        }
        // The deliberate default covers await, channel/select, Task launch,
        // call, RuntimeCall/FFI, lambda, new, member getter and future AST
        // expressions. No unknown call is silently assumed pure.
        fail(expr.getClass().getSimpleName());
    }
}
