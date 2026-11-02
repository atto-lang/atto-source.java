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
        verifyBody(stmt.body());
    }
    public static void verifyBody(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) verifyStatement(stmt);
    }
    private static void verifyStatement(Ast.Stmt stmt) {
        if (stmt instanceof Ast.ExprStmt expr) verifyExpr(expr.expression());
        else if (stmt instanceof Ast.BindingStmt binding) verifyExpr(binding.initializer());
        else if (stmt instanceof Ast.BlockStmt block) verifyBody(block.body());
        else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                verifyExpr(branch.condition());
                verifyBody(branch.body());
            }
            verifyBody(conditional.elseBody());
        } else reject();
    }
    private static void verifyExpr(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;
        if (expr instanceof Ast.BinaryExpr binary) {
            verifyExpr(binary.left());
            verifyExpr(binary.right());
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (!List.of("+", "-", "!", "not").contains(unary.operator())) reject();
            verifyExpr(unary.operand());
            return;
        }
        if (expr instanceof Ast.AssignExpr assign
                && assign.target() instanceof Ast.NameExpr) {
            verifyExpr(assign.value());
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            verifyExpr(conditional.condition());
            verifyExpr(conditional.whenTrue());
            verifyExpr(conditional.whenFalse());
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
