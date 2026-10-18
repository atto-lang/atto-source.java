package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallEvaluationSchedule;
import dev.oreslang.types.OptionalCallLiteralStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executable checks of a non-executable literal-only compiler proof. */
final class OptionalCallLiteralStageTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");

    private static List<Ast.Param> signature() {
        return List.of(new Ast.Param(INT, "a"),
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"),
                new Ast.Param(BOOL, "enabled"));
    }

    @Test
    void namedReorderedScalarsAreDerivedWithoutCallerTypes() {
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true)),
                        Ast.CallArgument.omit("label"),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(42L))));
        var proof = OptionalCallLiteralStage.prepareDirectLiteralCall(call, signature());
        assertEquals(List.of(BOOL, INT), proof.independentlyDerivedTypes());
        assertEquals(List.of(2, 1, 0), proof.invocation().arguments().abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertEquals(List.of(1, -1, 0), proof.invocation().arguments().abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                proof.invocation().arguments().abiSlots().get(1).action());
        assertEquals(2, proof.evaluation().sourceBindings().size());
        assertEquals(0, proof.evaluation().sourceBindings().get(0).evaluatedIndex());
        assertEquals(2, proof.evaluation().sourceBindings().get(0).formalIndex());
        assertThrows(UnsupportedOperationException.class,
                () -> proof.independentlyDerivedTypes().clear());
    }

    @Test
    void suppliedValueBecomesTypedSomeButOmissionIsTypedNone() {
        List<Ast.Param> optional = List.of(
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"));
        var supplied = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("label", new Ast.LiteralExpr("hi"))));
        var some = OptionalCallLiteralStage.prepareDirectLiteralCall(supplied, optional);
        assertEquals(OptionalCallAbiPreview.Action.WRAP_SOME,
                some.invocation().arguments().abiSlots().getFirst().action());
        assertEquals(new Ast.TypeRef("Option", List.of(STRING), false),
                some.invocation().arguments().abiSlots().getFirst().calleeType());

        var omitted = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.omit("label")));
        var none = OptionalCallLiteralStage.prepareDirectLiteralCall(omitted, optional);
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                none.invocation().arguments().abiSlots().getFirst().action());
        assertTrue(none.evaluation().sourceBindings().isEmpty());
        assertEquals(List.of(
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE, -1),
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 0),
                new OptionalCallEvaluationSchedule.Step(
                        OptionalCallEvaluationSchedule.Operation.INVOKE, -1)),
                none.evaluation().steps());
    }

    @Test
    void wrongScalarTypeAndRequiredOptionRemainRejected() {
        var wrong = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("a", new Ast.LiteralExpr("wrong")),
                        Ast.CallArgument.named("enabled", new Ast.LiteralExpr(true))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(wrong, signature()));
        var requiredOption = List.of(new Ast.Param(
                new Ast.TypeRef("Option", List.of(STRING), false), "value"));
        var rawString = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("value", new Ast.LiteralExpr("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(
                        rawString, requiredOption));
    }

    @Test
    void nonliteralNoneUnknownAndSideEffectCallsCannotForgeLiteralProof() {
        var optional = List.of(new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "x"));
        for (Ast.Expr unproved : List.of(new Ast.NameExpr("None"),
                new Ast.NameExpr("userValue"),
                new Ast.CallExpr(new Ast.NameExpr("sideEffect"), List.of()))) {
            var call = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                    List.of(Ast.CallArgument.named("x", unproved)));
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallLiteralStage.prepareDirectLiteralCall(call, optional));
        }
        var nil = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr(null))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(nil, optional));
    }

    @Test
    void dynamicOrMemberCalleeAndGenericApplicationCannotEnterLiteralPath() {
        var optional = List.of(new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "x"));
        var args = List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr("hello")));
        var method = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("receiver"), "f"),
                List.of(), false, args);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(method, optional));
        var generic = new Ast.StagedCallExpr(
                new Ast.NameExpr("f"), List.of(STRING), true, args);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallLiteralStage.prepareDirectLiteralCall(generic, optional));
    }

    @Test
    void directScalarsRejectIncorrectClaimedTypeInGeneralAbiPreview() {
        var params = List.of(new Ast.Param(INT, "x"));
        var call = List.of(Ast.CallArgument.named("x", new Ast.LiteralExpr(7L)));
        var plan = dev.oreslang.types.OptionalCallShapeBinder.bindParsed(
                dev.oreslang.types.OptionalCallShapeBinder.declaredParameterShapes(params), call);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        params, call, plan, List.of(STRING)));
    }
}
