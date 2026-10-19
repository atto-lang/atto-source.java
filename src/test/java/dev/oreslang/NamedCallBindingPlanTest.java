package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.NamedCallBindingPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NamedCallBindingPlanTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef VOID = Ast.TypeRef.simple("void");
    private static final Ast.TypeRef CALLBACK =
            Ast.TypeRef.functionType(List.of(INT), VOID);

    private static Ast.Param p(String name, Ast.TypeRef type) {
        return new Ast.Param(type, name);
    }

    private static Ast.LambdaExpr callback() {
        return new Ast.LambdaExpr(
                List.of(new Ast.Param(INT, "chunk")),
                null,
                List.of(new Ast.ReturnStmt(null)));
    }

    @Test
    void reordersNamedSlotsWithoutReorderingSourceEvaluation() {
        List<Ast.Param> formals = List.of(p("first", INT), p("second", INT));
        Ast.Expr effect2 = new Ast.CallExpr(new Ast.NameExpr("effect2"), List.of());
        Ast.Expr effect1 = new Ast.CallExpr(new Ast.NameExpr("effect1"), List.of());
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("second", effect2),
                Ast.CallArgument.named("first", effect1));

        NamedCallBindingPlan.Plan plan =
                NamedCallBindingPlan.bindRequired(formals, items);
        assertEquals(List.of(1, 0), plan.formalIndexBySourceItem());
        assertEquals(List.of(1, 0), plan.sourceItemIndexByFormal());
        assertEquals(List.of(effect2, effect1),
                NamedCallBindingPlan.evaluatedExpressionsInSourceOrder(
                        formals, items, plan));
    }

    @Test
    void acceptsLeadingPositionalThenNamedExactRequiredCall() {
        List<Ast.Param> formals =
                List.of(p("first", INT), p("second", INT), p("third", INT));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.value(new Ast.LiteralExpr(1L)),
                Ast.CallArgument.named("third", new Ast.LiteralExpr(3L)),
                Ast.CallArgument.named("second", new Ast.LiteralExpr(2L)));

        NamedCallBindingPlan.Plan plan =
                NamedCallBindingPlan.bindRequired(formals, items);
        assertEquals(List.of(0, 2, 1), plan.formalIndexBySourceItem());
        assertEquals(List.of(0, 2, 1), plan.sourceItemIndexByFormal());
    }

    @Test
    void trailingClosureMustBeOnlyRemainingFinalCallableFormal() {
        List<Ast.Param> formals =
                List.of(p("region", INT), p("layout", INT), p("body", CALLBACK));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("layout", new Ast.LiteralExpr(2L)),
                Ast.CallArgument.named("region", new Ast.LiteralExpr(1L)),
                Ast.CallArgument.trailingClosure(callback()));

        NamedCallBindingPlan.Plan plan =
                NamedCallBindingPlan.bindRequired(formals, items);
        assertEquals(2, plan.trailingClosureFormalIndex());
        assertEquals(List.of(1, 0, 2), plan.formalIndexBySourceItem());
        assertEquals(List.of(1, 0, 2), plan.sourceItemIndexByFormal());
    }

    @Test
    void trailingClosureRejectsNonCallableFinalFormal() {
        List<Ast.Param> formals = List.of(p("first", INT), p("last", INT));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("first", new Ast.LiteralExpr(1L)),
                Ast.CallArgument.trailingClosure(callback()));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bindRequired(formals, items));
        assertTrue(failure.getMessage().contains("Fnc"), failure::toString);
    }

    @Test
    void trailingClosureRejectsWhenAnyEarlierFormalIsStillMissing() {
        List<Ast.Param> formals =
                List.of(p("first", INT), p("middle", INT), p("body", CALLBACK));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("first", new Ast.LiteralExpr(1L)),
                Ast.CallArgument.trailingClosure(callback()));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bindRequired(formals, items));
        assertTrue(failure.getMessage().contains("exactly the final formal"),
                failure::toString);
    }

    @Test
    void unknownDuplicateAndMissingNamesFailClosed() {
        List<Ast.Param> formals = List.of(p("first", INT), p("second", INT));

        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bindRequired(formals, List.of(
                        Ast.CallArgument.named("unknown", new Ast.LiteralExpr(1L)),
                        Ast.CallArgument.named("second", new Ast.LiteralExpr(2L)))));

        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bindRequired(formals, List.of(
                        Ast.CallArgument.named("first", new Ast.LiteralExpr(1L)),
                        Ast.CallArgument.named("first", new Ast.LiteralExpr(2L)))));

        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.bindRequired(formals, List.of(
                        Ast.CallArgument.named("first", new Ast.LiteralExpr(1L)))));
    }

    @Test
    void checkedProofPermutesOnlyAlreadyEvaluatedValues() {
        Ast.NamedCallProof proof = new Ast.NamedCallProof(
                List.of(1, 0),
                List.of(1, 0),
                List.of("first", "second"),
                -1,
                "function",
                "join");

        assertEquals(
                List.of("source-for-first", "source-for-second"),
                proof.valuesInFormalOrder(
                        List.of("source-for-second", "source-for-first")));
        assertDoesNotThrow(() -> proof.requireFormalNames(
                List.of(p("first", INT), p("second", INT)),
                "join"));

        assertThrows(IllegalStateException.class, () -> proof.requireFormalNames(
                List.of(p("renamed", INT), p("second", INT)),
                "join"));
    }

    @Test
    void checkedProofRejectsForgedOrNonInverseMappings() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.NamedCallProof(
                List.of(0, 0),
                List.of(0, 1),
                List.of("first", "second"),
                -1,
                "function",
                "join"));

        assertThrows(IllegalArgumentException.class, () -> new Ast.NamedCallProof(
                List.of(1, 0),
                List.of(0, 1),
                List.of("first", "second"),
                -1,
                "function",
                "join"));
    }

    @Test
    void planVerificationRejectsForgedPermutation() {
        List<Ast.Param> formals = List.of(p("first", INT), p("second", INT));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("second", new Ast.LiteralExpr(2L)),
                Ast.CallArgument.named("first", new Ast.LiteralExpr(1L)));
        NamedCallBindingPlan.Plan forged =
                new NamedCallBindingPlan.Plan(List.of(0, 1), List.of(0, 1), -1);

        assertThrows(IllegalArgumentException.class,
                () -> NamedCallBindingPlan.verifyAgainstSource(
                        formals, items, forged));
    }
}
