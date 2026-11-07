package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.CriticalEffectPreflight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Static preflight only: these successes are NOT runtime ticket admission. */
final class CriticalBodyEffectsTest {
    private static Ast.Expr name(String value) { return new Ast.NameExpr(value); }
    private static Ast.Expr integer(int value) { return new Ast.LiteralExpr(value); }
    private static Ast.Stmt expression(Ast.Expr expr) { return new Ast.ExprStmt(expr); }

    private static void reject(Ast.Stmt statement, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CriticalEffectPreflight.verify(List.of(statement)));
        assertTrue(failure.getMessage().contains(diagnostic), failure::toString);
    }

    @Test void ordinaryScalarArithmeticAndAssignmentAreNonSuspending() {
        Ast.Expr addition = new Ast.BinaryExpr("+", name("value"), integer(1));
        var binding = new Ast.BindingStmt(
                Ast.BindingKind.LET, Ast.TypeRef.simple("int"), "value", integer(7));
        var assignment = expression(new Ast.AssignExpr(name("value"), addition));
        assertDoesNotThrow(() -> CriticalEffectPreflight.verify(List.of(
                binding, assignment, new Ast.ReturnStmt(name("value")))));
    }

    @Test void conditionalBranchesAreCheckedRecursively() {
        var safe = new Ast.IfStmt(
                List.of(new Ast.IfBranch(
                        new Ast.BinaryExpr(">", name("value"), integer(0)),
                        List.of(expression(new Ast.AssignExpr(name("value"), integer(3)))))),
                List.of(expression(new Ast.AssignExpr(name("value"), integer(2)))));
        assertDoesNotThrow(() -> CriticalEffectPreflight.verify(List.of(safe)));

        var unsafe = new Ast.IfStmt(
                List.of(new Ast.IfBranch(
                        new Ast.LiteralExpr(true),
                        List.of(new Ast.ReturnStmt(new Ast.AwaitExpr(name("future")))))),
                List.of());
        reject(unsafe, "AwaitExpr");
    }

    @Test void directAwaitNeverCrossesProtectedBody() {
        reject(new Ast.ReturnStmt(new Ast.AwaitExpr(name("future"))), "AwaitExpr");
        reject(expression(new Ast.AwaitExpr(name("future"))), "AwaitExpr");
        reject(new Ast.BlockStmt(List.of(
                new Ast.BlockStmt(List.of(expression(new Ast.AwaitExpr(name("future"))))))),
                "AwaitExpr");
    }

    @Test void allUnverifiedCallsAndHostFfiFailClosed() {
        reject(expression(new Ast.CallExpr(name("do_work"), List.of())), "CallExpr");
        reject(expression(new Ast.RuntimeCallExpr("blocking_host_read", List.of())),
                "RuntimeCallExpr");
        reject(expression(new Ast.NewExpr(Ast.TypeRef.simple("Box"), List.of())),
                "NewExpr");
        reject(expression(new Ast.LambdaExpr(
                List.of(), integer(1), null, false)), "LambdaExpr");
    }

    @Test void channelTaskAndScopeEffectsFailClosed() {
        reject(expression(new Ast.ChannelOpExpr(
                Ast.ChannelOperation.READ,
                Ast.WaitMode.BLOCKING, name("ch"), null, false, List.of())),
                "ChannelOpExpr");
        reject(expression(new Ast.TaskExpr(
                Ast.ExecutionDomain.CPU, List.of(new Ast.ReturnStmt(integer(1))))),
                "TaskExpr");
        reject(new Ast.TaskScopeStmt(List.of()), "TaskScopeStmt");
        reject(new Ast.SelectStmt(Ast.WaitMode.BLOCKING,
                Ast.SelectPolicy.FAIR,
                List.of(new Ast.SelectArm(Ast.ChannelOperation.READ, name("ch"),
                        null, Ast.BindingKind.VAL, "x", List.of()))),
                "SelectStmt");
    }

    @Test void nestedGuardsAndDeferCannotBypassLinearCleanupProof() {
        reject(new Ast.CriticalStmt(List.of()), "CriticalStmt");
        reject(new Ast.TryCriticalStmt(List.of(), List.of()), "TryCriticalStmt");
        reject(new Ast.DeferStmt(name("do_work")), "DeferStmt");
    }

    @Test void dynamicPropertyMutationCannotInvokeUnverifiedSetters() {
        reject(expression(new Ast.AssignExpr(
                new Ast.MemberExpr(name("object"), "value"), integer(1))),
                "dynamic assignment target");
        reject(expression(new Ast.IndexExpr(name("array"), integer(0))),
                "IndexExpr");
        reject(expression(new Ast.MemberExpr(name("object"), "value")),
                "MemberExpr");
    }

    @Test void staticWorkBudgetRejectsOversizedNestedBodies() {
        Ast.Stmt nested = expression(integer(1));
        for (int i = 0; i < 140; i++) nested = new Ast.BlockStmt(List.of(nested));
        final Ast.Stmt tooDeep = nested;
        reject(tooDeep, "bounded static effect-check budget");

        var many = new java.util.ArrayList<Ast.Stmt>();
        for (int i = 0; i < 5000; i++) many.add(expression(integer(i)));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CriticalEffectPreflight.verify(many));
        assertTrue(failure.getMessage().contains("bounded static effect-check budget"));
    }
}
