package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TaskCaptureAnalysisTest {
    private static Map<String, Boolean> uses(List<TaskCaptureAnalysis.Use> uses) {
        return uses.stream().collect(Collectors.toMap(
                TaskCaptureAnalysis.Use::name,
                TaskCaptureAnalysis.Use::write,
                Boolean::logicalOr,
                java.util.LinkedHashMap::new));
    }

    @Test
    void expressionMatchArmFreeNamesAreCaptured() {
        Ast.MatchExpr match = new Ast.MatchExpr(
                new Ast.NameExpr("subject"),
                List.of(new Ast.MatchValueArm(
                        new Ast.WildcardPattern(),
                        null,
                        new Ast.NameExpr("armOnly"))));

        Map<String, Boolean> found = uses(TaskCaptureAnalysis.taskBody(
                List.of(new Ast.ExprStmt(match))));

        assertEquals(Map.of("subject", false, "armOnly", false), found);
    }

    @Test
    void matchPatternBindingsDoNotBecomeOuterCaptures() {
        Ast.MatchExpr match = new Ast.MatchExpr(
                new Ast.NameExpr("subject"),
                List.of(new Ast.MatchValueArm(
                        new Ast.BindingPattern("bound"),
                        new Ast.BinaryExpr(
                                ">",
                                new Ast.NameExpr("bound"),
                                new Ast.NameExpr("threshold")),
                        new Ast.NameExpr("bound"))));

        Map<String, Boolean> found = uses(TaskCaptureAnalysis.taskBody(
                List.of(new Ast.ExprStmt(match))));

        assertEquals(Map.of("subject", false, "threshold", false), found);
        assertFalse(found.containsKey("bound"));
    }

    @Test
    void captureWritesAreMergedAcrossReadsAndWrites() {
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(
                List.of(),
                null,
                List.of(
                        new Ast.ExprStmt(new Ast.NameExpr("state")),
                        new Ast.ExprStmt(new Ast.AssignExpr(
                                new Ast.NameExpr("state"),
                                new Ast.LiteralExpr(1L)))));

        Map<String, Boolean> found = uses(
                TaskCaptureAnalysis.lambda(lambda, null));

        assertEquals(Boolean.TRUE, found.get("state"));
        assertEquals(1, found.size());
    }

    @Test
    void nestedTaskAndLexicalLambdaCapturesRemainTransitive() {
        Ast.LambdaExpr nested = new Ast.LambdaExpr(
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "local")),
                new Ast.BinaryExpr(
                        "+",
                        new Ast.NameExpr("outer"),
                        new Ast.NameExpr("local")),
                null);

        Ast.LambdaExpr parent = new Ast.LambdaExpr(
                List.of(),
                null,
                List.of(new Ast.ExprStmt(
                        new Ast.TaskExpr(List.of(
                                new Ast.ExprStmt(nested))))));

        Map<String, Boolean> found = uses(
                TaskCaptureAnalysis.lambda(parent, null));

        assertEquals(Map.of("outer", false), found);
    }

    @Test
    void nonLexicalNestedLambdaIsCaptureBarrier() {
        Ast.LambdaExpr nested = new Ast.LambdaExpr(
                List.of(),
                new Ast.NameExpr("outer"),
                null,
                true);
        Ast.LambdaExpr parent = new Ast.LambdaExpr(
                List.of(),
                nested,
                null);

        assertTrue(TaskCaptureAnalysis.lambda(parent, null).isEmpty());
    }
}
