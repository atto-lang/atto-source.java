package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TaskCaptureAnalysisSmoke {
    private static int checked;
    private TaskCaptureAnalysisSmoke() { }

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    private static Map<String, Boolean> uses(
            List<TaskCaptureAnalysis.Use> uses) {
        return uses.stream().collect(Collectors.toMap(
                TaskCaptureAnalysis.Use::name,
                TaskCaptureAnalysis.Use::write,
                Boolean::logicalOr,
                java.util.LinkedHashMap::new));
    }

    public static void main(String[] args) {
        var match = new Ast.MatchExpr(
                new Ast.NameExpr("subject"),
                List.of(new Ast.MatchValueArm(
                        new Ast.BindingPattern("bound"),
                        null,
                        new Ast.NameExpr("armOnly"))));
        var found = uses(TaskCaptureAnalysis.taskBody(
                List.of(new Ast.ExprStmt(match))));
        expect(found.equals(Map.of(
                        "subject", false,
                        "armOnly", false)),
                "match arm capture");
        expect(!found.containsKey("bound"),
                "pattern local excluded");

        var lambda = new Ast.LambdaExpr(
                List.of(),
                null,
                List.of(
                        new Ast.ExprStmt(new Ast.NameExpr("state")),
                        new Ast.ExprStmt(new Ast.AssignExpr(
                                new Ast.NameExpr("state"),
                                new Ast.LiteralExpr(1L)))));
        var captures = uses(TaskCaptureAnalysis.lambda(lambda, null));
        expect(Boolean.TRUE.equals(captures.get("state")),
                "write capture merged");
        expect(captures.size() == 1,
                "capture deduplicated");

        System.out.println(
                "TaskCaptureAnalysisSmoke PASS assertions=" + checked);
    }
}
