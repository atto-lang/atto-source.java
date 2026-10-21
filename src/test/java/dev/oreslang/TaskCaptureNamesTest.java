package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.TaskCaptureNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TaskCaptureNamesTest {
    @Test
    void emptyAndLiteralOnlyTasksHaveNoParentCaptures() {
        assertEquals(Set.of(), TaskCaptureNames.referencedIn(List.of()));
        assertEquals(Set.of(), TaskCaptureNames.referencedIn(
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(42L)))));
    }

    @Test
    void capturesAreTransitiveAcrossNestedTasksAndClosures() {
        Ast.TaskExpr nested = new Ast.TaskExpr(Ast.ExecutionDomain.CPU,
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("nestedOwner"))));
        Ast.LambdaExpr closure = new Ast.LambdaExpr(List.of(),
                new Ast.NameExpr("closureOwner"), null);
        assertEquals(Set.of("outerOwner", "nestedOwner", "closureOwner"),
                TaskCaptureNames.referencedIn(List.of(
                        new Ast.ExprStmt(new Ast.NameExpr("outerOwner")),
                        new Ast.ExprStmt(nested),
                        new Ast.ExprStmt(closure))));
    }

    @Test
    void nonLexicalClosuresAreCaptureBarriers() {
        Ast.LambdaExpr closure = new Ast.LambdaExpr(List.of(),
                new Ast.NameExpr("notCaptured"), null, true);
        assertEquals(Set.of("actual"),
                TaskCaptureNames.referencedIn(List.of(
                        new Ast.ExprStmt(closure),
                        new Ast.ExprStmt(new Ast.NameExpr("actual")))));
    }

    @Test
    void controlFlowAndDeferredCleanupContributeAllReferences() {
        assertEquals(Set.of("test", "left", "right", "cleanup"),
                TaskCaptureNames.referencedIn(List.of(
                        new Ast.IfStmt(
                                List.of(new Ast.IfBranch(
                                        new Ast.NameExpr("test"),
                                        List.of(new Ast.ExprStmt(new Ast.NameExpr("left"))))),
                                List.of(new Ast.ExprStmt(new Ast.NameExpr("right")))),
                        new Ast.DeferStmt(new Ast.NameExpr("cleanup")))));
    }

    @Test
    void nestedTaskNameInventoryDoesNotRequireStartingTheChild() {
        // A capture inventory cannot run task code, schedule work, or await.
        Ast.TaskExpr nested = new Ast.TaskExpr(Ast.ExecutionDomain.GPU,
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("deviceBuffer"))));
        assertEquals(Set.of("deviceBuffer"),
                TaskCaptureNames.referencedIn(List.of(new Ast.ExprStmt(nested))));
    }
}
