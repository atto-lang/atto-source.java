package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Direct AST proof cases, independent of parser spelling changes. */
final class ResultMustUseControlFlowTest {
    private static Ast.MatchStmt handles(String name) {
        return new Ast.MatchStmt(new Ast.NameExpr(name), true, List.of(
                new Ast.MatchArm(new Ast.ConstructorPattern("Ok", List.of()), null, List.of()),
                new Ast.MatchArm(new Ast.ConstructorPattern("Err",
                        List.of(new Ast.BindingPattern("error"))), null, List.of())));
    }

    @Test
    void directExplicitMatchHandlesPendingResult() {
        assertDoesNotThrow(() -> ResultMustUseProof.requireHandled(
                List.of(handles("result")), "result"));
    }

    @Test
    void awaitCannotCrossOutstandingObligationEvenWhenLaterMatched() {
        Ast.ExprStmt suspended = new Ast.ExprStmt(
                new Ast.AwaitExpr(new Ast.NameExpr("future")));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ResultMustUseProof.requireHandled(
                        List.of(suspended, handles("result")), "result"));
        assertTrue(exception.getMessage().contains("crosses await"));
    }

    @Test
    void suspensionInConditionalGuardFailsClosed() {
        Ast.IfStmt conditional = new Ast.IfStmt(
                List.of(new Ast.IfBranch(
                        new Ast.AwaitExpr(new Ast.NameExpr("future")),
                        List.of(handles("result")))),
                List.of(handles("result")));
        assertThrows(IllegalArgumentException.class,
                () -> ResultMustUseProof.requireHandled(List.of(conditional), "result"));
    }

    @Test
    void defersCannotCapturePendingResultWithoutCleanupProof() {
        Ast.DeferStmt cleanup = new Ast.DeferStmt(
                new Ast.CallExpr(new Ast.NameExpr("cleanup"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ResultMustUseProof.requireHandled(
                        List.of(cleanup, handles("result")), "result"));
    }

    @Test
    void matchWildcardOrUnprovenGuardsCannotDischarge() {
        Ast.MatchStmt wildcard = new Ast.MatchStmt(
                new Ast.NameExpr("result"), true,
                List.of(new Ast.MatchArm(new Ast.WildcardPattern(), null, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> ResultMustUseProof.requireHandled(List.of(wildcard), "result"));
        Ast.MatchStmt guarded = new Ast.MatchStmt(new Ast.NameExpr("result"), true, List.of(
                new Ast.MatchArm(new Ast.ConstructorPattern("Ok", List.of()),
                        new Ast.LiteralExpr(true), List.of()),
                new Ast.MatchArm(new Ast.ConstructorPattern("Err",
                        List.of(new Ast.BindingPattern("error"))), null, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> ResultMustUseProof.requireHandled(List.of(guarded), "result"));
    }
}
