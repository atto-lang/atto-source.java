package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MatchExpr effects must be scanned before granting safe return cleanup. */
final class ReturnCleanupMatchExprTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.Program withDefer(Ast.Expr cleanup) {
        Ast.FunctionDecl fn = new Ast.FunctionDecl("f", Ast.Visibility.PRIVATE,
                false, List.of(), List.of(), INT, List.of(), List.of(
                        new Ast.BindingStmt(Ast.BindingKind.LET, INT, "result",
                                new Ast.LiteralExpr(1L)),
                        new Ast.DeferStmt(cleanup),
                        new Ast.ReturnStmt(new Ast.NameExpr("result"))));
        return new Ast.Program(List.of(
                new Ast.ModuleDecl("app", List.of(fn))));
    }

    private static Ast.MatchExpr valueMatch(Ast.Expr value) {
        return new Ast.MatchExpr(new Ast.LiteralExpr(true), List.of(
                new Ast.MatchValueArm(new Ast.LiteralPattern(true), null, value),
                new Ast.MatchValueArm(new Ast.LiteralPattern(false), null,
                        new Ast.LiteralExpr(0L))));
    }

    @Test
    void deferredValueMatchRebindingReturnedBindingFails() {
        Ast.Expr write = new Ast.AssignExpr(new Ast.NameExpr("result"),
                new Ast.LiteralExpr(2L));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(withDefer(valueMatch(write))));
        assertTrue(failure.getMessage().contains("reassign returned binding"),
                failure::toString);
    }

    @Test
    void nestedDeferredValueMatchCannotHideRebinding() {
        Ast.Expr write = new Ast.AssignExpr(new Ast.NameExpr("result"),
                new Ast.LiteralExpr(3L));
        Ast.MatchExpr nested = valueMatch(valueMatch(write));
        assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(withDefer(nested)));
    }

    @Test
    void deferredGuardSideEffectsCannotHideRebinding() {
        Ast.Expr write = new Ast.AssignExpr(new Ast.NameExpr("result"),
                new Ast.LiteralExpr(3L));
        Ast.MatchExpr guarded = new Ast.MatchExpr(
                new Ast.LiteralExpr(true), List.of(
                        new Ast.MatchValueArm(new Ast.LiteralPattern(true),
                                write, new Ast.LiteralExpr(2L)),
                        new Ast.MatchValueArm(new Ast.LiteralPattern(false),
                                null, new Ast.LiteralExpr(0L))));
        assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(withDefer(guarded)));
    }

    @Test
    void shadowedArmBindingDoesNotRebindOuterReturnedValue() {
        Ast.Expr localWrite = new Ast.AssignExpr(new Ast.NameExpr("result"),
                new Ast.LiteralExpr(3L));
        Ast.MatchExpr shadowed = new Ast.MatchExpr(
                new Ast.LiteralExpr(7L), List.of(
                        new Ast.MatchValueArm(new Ast.BindingPattern("result"),
                                null, localWrite)));
        assertDoesNotThrow(
                () -> ReturnCleanupChecker.check(withDefer(shadowed)));
    }

    @Test
    void harmlessDeferredValueMatchMayReturnOuterValue() {
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(
                withDefer(valueMatch(new Ast.LiteralExpr(10L)))));
    }

    @Test
    void directCleanupCheckerRejectsUnloweredStagedArgumentCall() {
        Ast.Expr staged = new Ast.StagedCallExpr(new Ast.NameExpr("bad"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("value", new Ast.LiteralExpr(1L))));
        assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(withDefer(staged)));
    }
}
