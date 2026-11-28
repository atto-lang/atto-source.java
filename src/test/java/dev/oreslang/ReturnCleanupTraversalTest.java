package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct AST tests isolate the cleanup verifier from unrelated feature admission.
 * Source tests additionally require the checked compiler entry point to reject
 * the invalid transfer for the cleanup-specific reason.
 */
final class ReturnCleanupTraversalTest {
    private static Ast.NameExpr name(String name) { return new Ast.NameExpr(name); }
    private static Ast.LiteralExpr lit(Object value) { return new Ast.LiteralExpr(value); }
    private static Ast.TypeRef type(String name) { return Ast.TypeRef.simple(name); }
    private static Ast.AssignExpr rebind(String name) {
        return new Ast.AssignExpr(name(name), lit(2L));
    }
    private static Ast.Program program(Ast.Stmt... body) {
        var fn = new Ast.FunctionDecl("compute", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, List.of(), List.of(), type("int"),
                List.of(), List.of(body));
        return new Ast.Program(List.of(new Ast.ModuleDecl("Test", List.of(fn))));
    }
    private static Ast.BindingStmt local(String name) {
        return new Ast.BindingStmt(Ast.BindingKind.LET, type("int"), name, lit(1L));
    }
    private static void assertRebindRejected(Ast.Expr deferred) {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                ReturnCleanupChecker.check(program(local("answer"),
                        new Ast.DeferStmt(deferred), new Ast.ReturnStmt(name("answer")))));
        assertTrue(failure.getMessage().contains("defer cannot reassign returned binding 'answer'"),
                failure::getMessage);
    }

    @Test
    void postTestLoopCannotHideReturnFromFinally() {
        String source = """
                fnc compute(): int {
                    try { return 1; } finally {
                        do { return 2; } while (false);
                    }
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("return is not allowed inside finally"),
                failure::getMessage);
    }

    @Test
    void loopLocalBreakInsideFinallyIsAllowed() {
        var loop = new Ast.DoWhileStmt(List.of(new Ast.BreakStmt()), lit(false));
        var attempted = new Ast.TryStmt(List.of(new Ast.ReturnStmt(lit(1L))),
                null, List.of(), List.of(loop));
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(program(attempted)));
    }

    @Test
    void deferredLoopBodyCannotRebindPendingReturn() {
        var loop = new Ast.DoWhileStmt(List.of(new Ast.ExprStmt(rebind("answer"))), lit(false));
        assertRebindRejected(new Ast.LambdaExpr(List.of(), null, List.of(loop), false));
    }

    @Test
    void deferredLoopConditionCannotRebindPendingReturn() {
        var loop = new Ast.DoWhileStmt(List.of(),
                new Ast.BinaryExpr("eq", rebind("answer"), lit(2L)));
        assertRebindRejected(new Ast.LambdaExpr(List.of(), null, List.of(loop), false));
    }

    @Test
    void deferredExpressionWrappersCannotHideRebinding() {
        for (Ast.Expr wrapped : List.of(
                new Ast.NamedArgExpr("value", rebind("answer")),
                new Ast.WithExpr(name("record"), List.of(new Ast.WithField("value", rebind("answer")))),
                new Ast.MapLiteralExpr(null, List.of(new Ast.MapLiteralEntry(lit(0L), rebind("answer")))),
                new Ast.MapLiteralExpr(null, List.of(new Ast.MapLiteralEntry(rebind("answer"), lit(0L)))),
                new Ast.LocalAggregateConstructExpr("Record", List.of(rebind("answer")), List.of()),
                new Ast.LocalAggregateConstructExpr("Record", List.of(),
                        List.of(new Ast.ObjectField("value", rebind("answer")))))) {
            assertRebindRejected(wrapped);
        }
    }

    @Test
    void recordPatternBindingDoesNotAliasOuterReturnedName() {
        var pattern = new Ast.RecordPattern(false,
                List.of(new Ast.RecordFieldPattern("field", new Ast.BindingPattern("answer"))));
        var arm = new Ast.MatchValueArm(pattern, null, rebind("answer"));
        var expression = new Ast.MatchExpr(name("record"), List.of(arm));
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(program(local("answer"),
                new Ast.DeferStmt(expression), new Ast.ReturnStmt(name("answer")))));
    }

    @Test
    void scopedLoanBodyDoesNotHideReturnFromFinally() {
        var loan = new Ast.UsingBorrowStmt(name("resource"), "view",
                List.of(new Ast.ReturnStmt(lit(2L))));
        var attempted = new Ast.TryStmt(List.of(new Ast.ReturnStmt(lit(1L))),
                null, List.of(), List.of(loan));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(program(attempted)));
        assertTrue(failure.getMessage().contains("return is not allowed inside finally"));
    }
}
