package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Free-variable plan is not Sendable evidence or serializer authorization. */
final class LambdaCapturePlannerTest {
    private static Ast.NameExpr name(String s) { return new Ast.NameExpr(s); }
    private static Ast.LambdaExpr expression(Ast.Expr e) {
        return new Ast.LambdaExpr(List.of(), e, null);
    }
    private static Ast.LambdaExpr statements(Ast.Stmt... body) {
        return new Ast.LambdaExpr(List.of(), null, List.of(body));
    }

    @Test void emptyAndLiteralCallbacksRetainNoImplicitReceiver() {
        assertEquals(Set.of(), LambdaCapturePlanner.captures(
                expression(new Ast.LiteralExpr(42))));
        assertEquals(Set.of(), LambdaCapturePlanner.captures(
                statements(new Ast.ReturnStmt(new Ast.LiteralExpr(7)))));
    }

    @Test void capturesOnlyReferencedOwnerAndNeverUnrelatedSelfOrCapability() {
        assertEquals(Set.of("bias"), LambdaCapturePlanner.captures(
                expression(new Ast.BinaryExpr("+", name("bias"),
                        new Ast.LiteralExpr(1)))));
        assertEquals(Set.of("self"), LambdaCapturePlanner.captures(expression(name("self"))));
        assertEquals(Set.of("outer"), LambdaCapturePlanner.captures(statements(
                new Ast.BindingStmt(Ast.BindingKind.VAL,
                        Ast.TypeRef.simple("int"), "local", name("outer")),
                new Ast.ReturnStmt(name("local")))));
    }

    @Test void nestedClosureTransitiveCapturesRespectOwnParameters() {
        Ast.LambdaExpr nested = new Ast.LambdaExpr(
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "inner")),
                new Ast.BinaryExpr("+", name("outer"), name("inner")), null);
        assertEquals(Set.of("outer"), LambdaCapturePlanner.captures(expression(nested)));
        Ast.LambdaExpr nlex = new Ast.LambdaExpr(List.of(), name("secret"), null, true);
        assertEquals(Set.of(), LambdaCapturePlanner.captures(expression(nlex)));
    }

    @Test void shadowingSelfInsideLambdaNeverCapturesOuterReceiver() {
        Ast.LambdaExpr nested = expression(name("self"));
        Ast.LambdaExpr outer = statements(
                new Ast.BindingStmt(Ast.BindingKind.VAL,
                        Ast.TypeRef.simple("int"), "self", new Ast.LiteralExpr(10)),
                new Ast.ReturnStmt(nested));
        assertEquals(Set.of(), LambdaCapturePlanner.captures(outer));
    }

    @Test void patternArmsDoNotTreatLocallyBoundValuesAsCaptures() {
        Ast.MatchExpr matched = new Ast.MatchExpr(name("subject"),
                List.of(new Ast.MatchValueArm(
                        new Ast.BindingPattern("local"), null, name("local"))));
        assertEquals(Set.of("subject"), LambdaCapturePlanner.captures(expression(matched)));
    }

    @Test void conditionBindingsCannotStealOuterNames() {
        Ast.IfStmt branch = new Ast.IfStmt(List.of(
                new Ast.IfBranch(
                        new Ast.TypeTestExpr(name("subject"), Ast.TypeRef.simple("int"), "n"),
                        List.of(new Ast.ReturnStmt(name("n"))))),
                List.of(new Ast.ReturnStmt(name("fallback"))));
        assertEquals(Set.of("subject", "fallback"),
                LambdaCapturePlanner.captures(statements(branch)));
    }
}
