package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.ConditionCaptures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConditionCaptureMergeRegressionTest {
    @Test void nestedComparisonCapturesAreDiscovered() {
        Ast.Expr condition = new Ast.BinaryExpr("==",
                new Ast.CaptureExpr(new Ast.LiteralExpr(42), "value"),
                new Ast.LiteralExpr(42));
        var names = new LinkedHashSet<String>();
        ConditionCaptures.collectNames(condition, names);
        assertEquals(Set.of("value"), names);
    }

    @Test void captureNamesRemainAvailableOnShortCircuitLeftSide() {
        Ast.Expr condition = new Ast.BinaryExpr("&&",
                new Ast.CaptureExpr(new Ast.LiteralExpr(true), "ready"),
                new Ast.NameExpr("ready"));
        assertDoesNotThrow(() -> ConditionCaptures.validate(condition));
        var names = new LinkedHashSet<String>();
        ConditionCaptures.collectNames(condition, names);
        assertEquals(Set.of("ready"), names);
    }

    @Test void captureInConditionalShortCircuitRightSideIsRejected() {
        Ast.Expr condition = new Ast.BinaryExpr("&&", new Ast.LiteralExpr(true),
                new Ast.CaptureExpr(new Ast.LiteralExpr(1), "late"));
        assertThrows(IllegalArgumentException.class, () -> ConditionCaptures.validate(condition));
    }

    @Test void deferredLambdaAndTaskBodiesDoNotDefineConditionLocalNames() {
        Ast.Expr nested = new Ast.CaptureExpr(new Ast.LiteralExpr(3), "deferred");
        Ast.Expr lambda = new Ast.LambdaExpr(List.of(), nested, null);
        Ast.Expr task = new Ast.TaskExpr(Ast.ExecutionDomain.CPU,
                List.of(new Ast.ExprStmt(nested)));
        for (Ast.Expr boundary : List.of(lambda, task)) {
            assertFalse(ConditionCaptures.contains(boundary));
            assertDoesNotThrow(() -> ConditionCaptures.validate(boundary));
            var names = new LinkedHashSet<String>();
            ConditionCaptures.collectNames(boundary, names);
            assertTrue(names.isEmpty());
        }
    }

    @Test void allSealedExpressionVariantsAreExplicitlyAudited() {
        var observed = java.util.Arrays.stream(Ast.Expr.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "LiteralExpr", "NameExpr", "BinaryExpr", "UnaryExpr",
                "AssignExpr", "ConditionalExpr", "MatchExpr", "TypeTestExpr",
                "PatternTestExpr", "CastExpr", "CaptureExpr", "SpreadExpr",
                "CallExpr", "RuntimeCallExpr", "MemberExpr", "IndexExpr",
                "NewExpr", "AwaitExpr", "TaskExpr", "TrapExpr",
                "ChannelOpExpr", "DynamicSelectExpr", "ListExpr",
                "TupleExpr", "ObjectExpr", "LambdaExpr"), observed);
    }
}
