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
    void lexicalLocalsAndLambdaParametersAreNotParentCaptures() {
        Ast.BindingStmt local = new Ast.BindingStmt(
                Ast.BindingKind.CONST, null, "value",
                new Ast.LiteralExpr(41L));
        Ast.LambdaExpr lambda = new Ast.LambdaExpr(
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "argument")),
                new Ast.NameExpr("argument"), null);
        assertEquals(Set.of(), TaskCaptureNames.referencedIn(List.of(
                local,
                new Ast.ExprStmt(new Ast.NameExpr("value")),
                new Ast.ExprStmt(lambda))));
    }

    @Test
    void nestedTaskCanReadOuterTaskLocalWithoutCapturingCallerSlot() {
        Ast.BindingStmt local = new Ast.BindingStmt(
                Ast.BindingKind.CONST, null, "local",
                new Ast.LiteralExpr(41L));
        Ast.TaskExpr nested = new Ast.TaskExpr(
                Ast.ExecutionDomain.CPU,
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("local"))));
        assertEquals(Set.of(), TaskCaptureNames.referencedIn(List.of(
                local, new Ast.ExprStmt(nested))));
    }

    @Test
    void referenceBeforeLocalDeclarationIsConservativelyCaptured() {
        assertEquals(Set.of("possibleOuter"), TaskCaptureNames.referencedIn(
                List.of(
                        new Ast.ExprStmt(new Ast.NameExpr("possibleOuter")),
                        new Ast.BindingStmt(Ast.BindingKind.CONST,
                                null, "possibleOuter", new Ast.LiteralExpr(42L)),
                        new Ast.ExprStmt(new Ast.NameExpr("possibleOuter")))));
    }

    @Test
    void classicForInitializerCapturesOuterButLoopBindingShadowsBody() {
        Ast.ForStmt loop = new Ast.ForStmt(
                new Ast.BindingStmt(
                        Ast.BindingKind.VAL,
                        null,
                        "item",
                        new Ast.NameExpr("source")),
                new Ast.LiteralExpr(false),
                null,
                List.of(new Ast.ExprStmt(new Ast.NameExpr("item"))));
        assertEquals(Set.of("source"),
                TaskCaptureNames.referencedIn(List.of(loop)));
    }

    @Test
    void recursiveLambdaBindingDoesNotCaptureSameNamedParentSlot() {
        Ast.LambdaExpr recursive = new Ast.LambdaExpr(
                List.of(),
                new Ast.NameExpr("worker"),
                null);
        Ast.BindingStmt binding = new Ast.BindingStmt(
                Ast.BindingKind.VAL,
                null,
                "worker",
                recursive);
        assertEquals(Set.of(),
                TaskCaptureNames.referencedIn(List.of(binding)));
    }

    @Test
    void ifRefinementBindingIsBranchLocalNotParentCapture() {
        Ast.TypeTestExpr condition = new Ast.TypeTestExpr(
                new Ast.NameExpr("subject"),
                Ast.TypeRef.simple("Box"),
                "narrowed");
        Ast.IfStmt statement = new Ast.IfStmt(
                List.of(new Ast.IfBranch(
                        condition,
                        List.of(new Ast.ExprStmt(
                                new Ast.NameExpr("narrowed"))))),
                List.of());
        assertEquals(
                Set.of("subject"),
                TaskCaptureNames.referencedIn(List.of(statement)));
    }

    @Test
    void leftAndRefinementBindingShadowsSameNamedParentInRightOperand() {
        Ast.TypeTestExpr refinement = new Ast.TypeTestExpr(
                new Ast.NameExpr("subject"),
                Ast.TypeRef.simple("Box"),
                "narrowed");
        Ast.BinaryExpr condition = new Ast.BinaryExpr(
                "&&",
                refinement,
                new Ast.BinaryExpr(
                        ">",
                        new Ast.MemberExpr(
                                new Ast.NameExpr("narrowed"),
                                "value"),
                        new Ast.LiteralExpr(0L)));
        Ast.IfStmt statement = new Ast.IfStmt(
                List.of(new Ast.IfBranch(condition, List.of())),
                List.of());

        assertEquals(
                Set.of("subject"),
                TaskCaptureNames.referencedIn(List.of(statement)));
    }

    @Test
    void nestedTaskNameInventoryDoesNotRequireStartingTheChild() {
        // A capture inventory cannot run task code, schedule work, or await.
        Ast.TaskExpr nested = new Ast.TaskExpr(Ast.ExecutionDomain.GPU,
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("deviceBuffer"))));
        assertEquals(Set.of("deviceBuffer"),
                TaskCaptureNames.referencedIn(List.of(new Ast.ExprStmt(nested))));
    }

    @Test
    void valueMatchPrefixLocalShadowsParentForArmResult() {
        Ast.MatchExpr matched = new Ast.MatchExpr(
                new Ast.LiteralExpr(true),
                List.of(new Ast.MatchValueArm(
                        new Ast.WildcardPattern(),
                        null,
                        List.of(new Ast.BindingStmt(
                                Ast.BindingKind.VAL,
                                Ast.TypeRef.simple("int"),
                                "shadowed",
                                new Ast.LiteralExpr(41L))),
                        new Ast.BinaryExpr(
                                "+",
                                new Ast.NameExpr("shadowed"),
                                new Ast.NameExpr("actualParent")))));

        assertEquals(
                Set.of("actualParent"),
                TaskCaptureNames.referencedIn(
                        List.of(new Ast.ExprStmt(matched))));
    }

}
