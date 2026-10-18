package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallEvaluationSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Non-executable ordering tests: no runtime guest ABI is enabled here. */
final class OptionalCallEvaluationScheduleTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");

    private static List<Ast.Param> formals() {
        return List.of(new Ast.Param(INT, "a"),
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "b"),
                new Ast.Param(BOOL, "c"));
    }

    private static OptionalCallEvaluationSchedule.Step step(
            OptionalCallEvaluationSchedule.Operation operation, int index) {
        return new OptionalCallEvaluationSchedule.Step(operation, index);
    }

    @Test
    void namedReorderingPreservesCalleeAndLexicalEffectsBeforeFullAbi() {
        Ast.Expr callee = new Ast.MemberExpr(
                new Ast.CallExpr(new Ast.NameExpr("receiverEffect"), List.of()), "f");
        Ast.Expr c = new Ast.CallExpr(new Ast.NameExpr("effectC"), List.of());
        Ast.Expr a = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        var call = new Ast.StagedCallExpr(callee, List.of(), false, List.of(
                Ast.CallArgument.named("c", c), Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", a)));
        var preview = OptionalCallAbiPreview.prepareStagedCallExact(
                call, formals(), List.of(BOOL, INT));
        var schedule = OptionalCallEvaluationSchedule.from(preview);

        assertEquals(OptionalCallAbiPreview.InvocationKind.CALL, schedule.kind());
        assertEquals(List.of(callee, c, a), schedule.evaluationOrder());
        assertEquals(List.of(
                step(OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE, -1),
                step(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT, 0),
                step(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT, 1),
                step(OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 0),
                step(OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 1),
                step(OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 2),
                step(OptionalCallEvaluationSchedule.Operation.INVOKE, -1)),
                schedule.steps());
        assertEquals(List.of(1, -1, 0), schedule.abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertEquals(List.of(2, 1, 0), schedule.abiSlots().stream()
                .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                schedule.abiSlots().get(1).action());
        assertThrows(UnsupportedOperationException.class, () -> schedule.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> schedule.abiSlots().clear());
    }

    @Test
    void explicitOmissionAndTypedNoneHaveDifferentEvaluationCounts() {
        List<Ast.Param> parameters = List.of(
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "x"));
        var omitted = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.omit("x")));
        var omittedSchedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(omitted, parameters, List.of()));
        assertEquals(List.of(
                step(OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE, -1),
                step(OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, 0),
                step(OptionalCallEvaluationSchedule.Operation.INVOKE, -1)),
                omittedSchedule.steps());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                omittedSchedule.abiSlots().getFirst().action());

        var optionString = new Ast.TypeRef("Option", List.of(STRING), false);
        var supplied = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("x", new Ast.NameExpr("None"))));
        var suppliedSchedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(
                        supplied, parameters, List.of(optionString)));
        assertEquals(4, suppliedSchedule.steps().size());
        assertEquals(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT,
                suppliedSchedule.steps().get(1).operation());
        assertEquals(OptionalCallAbiPreview.Action.PASS_OPTION,
                suppliedSchedule.abiSlots().getFirst().action());
    }

    @Test
    void constructorsAndActorSpawnsEvaluateNoGuestCallTarget() {
        for (boolean actor : List.of(false, true)) {
            var exprC = new Ast.NameExpr("effectC");
            var exprA = new Ast.NameExpr("effectA");
            var expr = new Ast.StagedNewExpr(Ast.TypeRef.simple("Worker"), List.of(
                    Ast.CallArgument.named("c", exprC),
                    Ast.CallArgument.named("a", exprA)), actor);
            var schedule = OptionalCallEvaluationSchedule.from(
                    OptionalCallAbiPreview.prepareStagedConstructionExact(
                            expr, formals(), List.of(BOOL, INT)));
            assertEquals(actor ? OptionalCallAbiPreview.InvocationKind.ACTOR_SPAWN
                               : OptionalCallAbiPreview.InvocationKind.CLASS_CONSTRUCTOR,
                    schedule.kind());
            assertEquals(List.of(exprC, exprA), schedule.evaluationOrder());
            assertEquals(step(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT, 0),
                    schedule.steps().getFirst());
            assertEquals(step(OptionalCallEvaluationSchedule.Operation.INVOKE, -1),
                    schedule.steps().getLast());
            assertEquals(2, schedule.steps().stream()
                    .filter(s -> s.operation()
                            == OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT)
                    .count());
            assertEquals(3, schedule.abiSlots().size());
        }
    }

    @Test
    void malformedStepIndexesAreRejected() {
        assertThrows(NullPointerException.class,
                () -> step(null, -1));
        assertThrows(IllegalArgumentException.class,
                () -> step(OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT, -1));
        assertThrows(IllegalArgumentException.class,
                () -> step(OptionalCallEvaluationSchedule.Operation.MATERIALIZE_ABI_SLOT, -1));
        assertThrows(IllegalArgumentException.class,
                () -> step(OptionalCallEvaluationSchedule.Operation.EVALUATE_CALLEE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> step(OptionalCallEvaluationSchedule.Operation.INVOKE, 0));
        assertThrows(NullPointerException.class,
                () -> OptionalCallEvaluationSchedule.from(null));
    }

    @Test
    void sourceBindingsPreserveRawOriginsThroughReorderedExplicitOmission() {
        var c = new Ast.CallExpr(new Ast.NameExpr("effectC"), List.of());
        var a = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        var staged = new Ast.StagedCallExpr(new Ast.NameExpr("target"), List.of(), false,
                List.of(Ast.CallArgument.named("c", c),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", a)));
        var schedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(
                        staged, formals(), List.of(BOOL, INT)));
        assertEquals(List.of(
                new OptionalCallEvaluationSchedule.SourceBinding(
                        0, 0, 2, OptionalCallAbiPreview.Action.PASS_REQUIRED),
                new OptionalCallEvaluationSchedule.SourceBinding(
                        1, 2, 0, OptionalCallAbiPreview.Action.PASS_REQUIRED)),
                schedule.sourceBindings());
        assertThrows(UnsupportedOperationException.class,
                () -> schedule.sourceBindings().clear());
    }

    @Test
    void sameExpressionObjectAtTwoSourcePositionsMustNotDeduplicateEvaluation() {
        Ast.Expr shared = new Ast.CallExpr(new Ast.NameExpr("sideEffect"), List.of());
        var two = List.of(new Ast.Param(INT, "left"), new Ast.Param(INT, "right"));
        var staged = new Ast.StagedCallExpr(new Ast.NameExpr("target"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("right", shared),
                        Ast.CallArgument.named("left", shared)));
        var schedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(staged, two, List.of(INT, INT)));
        assertSame(shared, schedule.evaluationOrder().get(1));
        assertSame(shared, schedule.evaluationOrder().get(2));
        assertEquals(List.of(
                new OptionalCallEvaluationSchedule.SourceBinding(
                        0, 0, 1, OptionalCallAbiPreview.Action.PASS_REQUIRED),
                new OptionalCallEvaluationSchedule.SourceBinding(
                        1, 1, 0, OptionalCallAbiPreview.Action.PASS_REQUIRED)),
                schedule.sourceBindings());
        assertEquals(2, schedule.steps().stream()
                .filter(s -> s.operation()
                        == OptionalCallEvaluationSchedule.Operation.EVALUATE_ARGUMENT)
                .count());
    }

    @Test
    void omissionsProduceNoSourceBindingAndTypedNoneRemainsASuppliedExpression() {
        var only = List.of(new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"));
        var omission = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.omit("label")));
        var absentSchedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(
                        omission, only, List.of()));
        assertTrue(absentSchedule.sourceBindings().isEmpty());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                absentSchedule.abiSlots().getFirst().action());
        var option = new Ast.TypeRef("Option", List.of(STRING), false);
        var explicit = new Ast.StagedCallExpr(new Ast.NameExpr("f"), List.of(), false,
                List.of(Ast.CallArgument.named("label", new Ast.NameExpr("None"))));
        var valueSchedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedCallExact(
                        explicit, only, List.of(option)));
        assertEquals(List.of(new OptionalCallEvaluationSchedule.SourceBinding(
                        0, 0, 0, OptionalCallAbiPreview.Action.PASS_OPTION)),
                valueSchedule.sourceBindings());
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallEvaluationSchedule.SourceBinding(
                        0, 0, 0, OptionalCallAbiPreview.Action.INJECT_NONE));
    }

    @Test
    void constructionNamedSourceBindingsRetainLexicalOrderIndependentlyOfAbi() {
        var c = new Ast.NameExpr("cValue");
        var a = new Ast.NameExpr("aValue");
        var construction = new Ast.StagedNewExpr(
                Ast.TypeRef.simple("Worker"), List.of(
                        Ast.CallArgument.named("c", c),
                        Ast.CallArgument.omit("b"),
                        Ast.CallArgument.named("a", a)), false);
        var schedule = OptionalCallEvaluationSchedule.from(
                OptionalCallAbiPreview.prepareStagedConstructionExact(
                        construction, formals(), List.of(BOOL, INT)));
        assertEquals(2, schedule.sourceBindings().size());
        assertEquals(2, schedule.sourceBindings().getFirst().formalIndex());
        assertEquals(0, schedule.sourceBindings().getFirst().originalCallItemIndex());
        assertEquals(0, schedule.sourceBindings().getLast().formalIndex());
        assertEquals(2, schedule.sourceBindings().getLast().originalCallItemIndex());
    }

}
