package dev.oreslang.types;
import dev.oreslang.ast.Ast;
import java.util.List;
/** Closed, call-free scalar proof for the first immediate named-Mutex gate. */
public final class CriticalBodyPreflight {
    private CriticalBodyPreflight() { }
    public static void verify(Ast.CriticalStmt stmt) {
        if (!stmt.tryOnly() || stmt.gateName() == null)
            throw new IllegalArgumentException(
                "E_CRITICAL_UNLOWERED: queued/ambient gate needs scheduler lowering");
        verifyBody(stmt.body(), stmt.gateName());
    }
    private static void verifyBody(List<Ast.Stmt> body, String gate) {
        for (Ast.Stmt stmt : body) verifyStatement(stmt, gate);
    }
    private static void verifyStatement(Ast.Stmt stmt, String gate) {
        if (stmt instanceof Ast.ExprStmt expr) verifyExpr(expr.expression(), gate);
        else if (stmt instanceof Ast.BindingStmt binding) verifyExpr(binding.initializer(), gate);
        else if (stmt instanceof Ast.BlockStmt block) verifyBody(block.body(), gate);
        else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                verifyExpr(branch.condition(), gate);
                verifyBody(branch.body(), gate);
            }
            verifyBody(conditional.elseBody(), gate);
        } else reject();
    }
    private static void verifyExpr(Ast.Expr expr, String gate) {
        if (expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            // Moving/rebinding the very gate whose permit is held would let
            // another logical owner observe a different lock identity.
            if (name.name().equals(gate)) reject();
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            verifyExpr(binary.left(), gate);
            verifyExpr(binary.right(), gate);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (!List.of("+", "-", "!", "not").contains(unary.operator())) reject();
            verifyExpr(unary.operand(), gate);
            return;
        }
        if (expr instanceof Ast.AssignExpr assign
                && assign.target() instanceof Ast.NameExpr target
                && !target.name().equals(gate)) {
            verifyExpr(assign.value(), gate);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            verifyExpr(conditional.condition(), gate);
            verifyExpr(conditional.whenTrue(), gate);
            verifyExpr(conditional.whenFalse(), gate);
            return;
        }
        // No calls, members/indexing, closures, allocation, I/O, await, yield,
        // nested critical, defer, select/channel operations, or unknown ASTs.
        reject();
    }
    private static void reject() {
        throw new IllegalArgumentException(
            "E_CRITICAL_BODY_EFFECT: protected body has unproven effects");
    }
}
