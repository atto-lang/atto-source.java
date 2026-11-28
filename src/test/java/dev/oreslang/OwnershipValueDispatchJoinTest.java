package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Ownership-only controls isolate the join proof from unrelated type admission. */
final class OwnershipValueDispatchJoinTest {
    private static Ast.NameExpr name(String value) { return new Ast.NameExpr(value); }
    private static Ast.LiteralExpr lit(Object value) { return new Ast.LiteralExpr(value); }
    private static Ast.BindingStmt bind(String name, Ast.Expr value) {
        return new Ast.BindingStmt(Ast.BindingKind.CONST, null, name, value);
    }
    private static Ast.Program program(Ast.Stmt... body) {
        var fn = new Ast.FunctionDecl("compute", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, List.of(),
                List.of(new Ast.Param(Ast.TypeRef.simple("bool"), "flag")),
                Ast.TypeRef.simple("void"), List.of(), List.of(body));
        return new Ast.Program(List.of(new Ast.ModuleDecl("Test", List.of(fn))));
    }
    private static Ast.MatchExpr match(Ast.Expr first, Ast.Expr second) {
        return new Ast.MatchExpr(name("flag"), List.of(
                new Ast.MatchValueArm(new Ast.LiteralPattern(true), null, first),
                new Ast.MatchValueArm(new Ast.LiteralPattern(false), null, second)));
    }
    private static Ast.Expr take(String name) {
        return new Ast.RuntimeCallExpr("take", List.of(name(name)));
    }
    private static Ast.Expr loan(String name) {
        return new Ast.RuntimeCallExpr("borrow", List.of(name(name)));
    }

    @Test
    void conditionalCannotEraseLoanProvenanceFromEitherPredecessor() {
        for (Ast.ConditionalExpr result : List.of(
                new Ast.ConditionalExpr(name("flag"), loan("owner"), lit(false)),
                new Ast.ConditionalExpr(name("flag"), lit(false), loan("owner")),
                new Ast.ConditionalExpr(name("flag"), loan("owner"), loan("owner")))) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    OwnershipChecker.check(program(bind("owner", lit(1L)),
                            new Ast.ExprStmt(result))));
            assertTrue(error.getMessage().contains("conditional cannot return a borrowed"),
                    error::getMessage);
        }
    }

    @Test
    void matchCannotEraseLoanProvenanceFromLaterPredecessor() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                OwnershipChecker.check(program(bind("owner", lit(1L)),
                        new Ast.ExprStmt(match(lit(false), loan("owner"))))));
        assertTrue(error.getMessage().contains("value match cannot return a borrowed"),
                error::getMessage);
    }

    @Test
    void affineMatchResultIsNotCopyRegardlessOfArmOrder() {
        for (Ast.MatchExpr result : List.of(
                match(lit(false), new Ast.ListExpr(List.of(lit(1L)))),
                match(new Ast.ListExpr(List.of(lit(1L))), lit(false)))) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    OwnershipChecker.check(program(bind("joined", result),
                            bind("transferred", name("joined")),
                            new Ast.ExprStmt(name("joined")))));
            assertTrue(error.getMessage().contains("use of moved value 'joined'"),
                    error::getMessage);
        }
    }

    @Test
    void allCopyResultsRemainReusable() {
        assertDoesNotThrow(() -> OwnershipChecker.check(program(
                bind("joined", match(lit(true), lit(false))),
                bind("copy", name("joined")), new Ast.ExprStmt(name("joined")),
                bind("conditional", new Ast.ConditionalExpr(name("flag"), lit(1L), lit(2L))),
                bind("other", name("conditional")), new Ast.ExprStmt(name("conditional")))));
    }

    @Test
    void moveOnOnlyOnePredecessorStillPoisonsPostJoinUse() {
        for (Ast.Expr result : List.of(
                new Ast.ConditionalExpr(name("flag"), take("owner"), lit(0L)),
                new Ast.ConditionalExpr(name("flag"), lit(0L), take("owner")),
                match(take("owner"), lit(0L)), match(lit(0L), take("owner")))) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    OwnershipChecker.check(program(bind("owner", lit(1L)),
                            bind("result", result), new Ast.ExprStmt(name("owner")))));
            assertTrue(error.getMessage().contains("use of moved value 'owner'"),
                    error::getMessage);
        }
    }

    @Test
    void mutuallyExclusiveTransfersAreNotSequentialDoubleMoves() {
        for (Ast.Expr result : List.of(
                new Ast.ConditionalExpr(name("flag"), take("owner"), take("owner")),
                match(take("owner"), take("owner")))) {
            assertDoesNotThrow(() -> OwnershipChecker.check(program(
                    bind("owner", lit(1L)), bind("result", result))));
        }
    }
}
