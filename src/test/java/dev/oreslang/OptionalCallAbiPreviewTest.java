package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallShapeBinder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure planning assertions only; no guest ABI or interpreter execution. */
final class OptionalCallAbiPreviewTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");
    private static final Ast.TypeRef OPTION_STRING =
            new Ast.TypeRef("Option", List.of(STRING), false);

    private static List<Ast.Param> formals() {
        return List.of(
                new Ast.Param(INT, "a"),
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "b"),
                new Ast.Param(BOOL, "c"));
    }

    @Test
    void reorderedNamedCallProducesFullAbiWithSourceOrderAndTypedNone() {
        Ast.Expr effectC = new Ast.CallExpr(new Ast.NameExpr("sideC"), List.of());
        Ast.Expr effectA = new Ast.CallExpr(new Ast.NameExpr("sideA"), List.of());
        List<Ast.CallArgument> call = List.of(
                Ast.CallArgument.named("c", effectC),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", effectA));
        OptionalCallShapeBinder.Plan shape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals()), call);
        OptionalCallAbiPreview.Preview preview =
                OptionalCallAbiPreview.prepareExact(formals(), call, shape, List.of(BOOL, INT));

        assertEquals(3, preview.fullArity());
        assertSame(effectC, preview.sourceExpressions().get(0));
        assertSame(effectA, preview.sourceExpressions().get(1));
        var slots = preview.abiSlots();
        assertEquals(List.of(1, -1, 0),
                slots.stream().map(OptionalCallAbiPreview.Slot::evaluatedSourceIndex).toList());
        assertEquals(List.of(2, 1, 0),
                slots.stream().map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertEquals(List.of(
                OptionalCallAbiPreview.Action.PASS_REQUIRED,
                OptionalCallAbiPreview.Action.INJECT_NONE,
                OptionalCallAbiPreview.Action.PASS_REQUIRED),
                slots.stream().map(OptionalCallAbiPreview.Slot::action).toList());
        assertEquals(OPTION_STRING, slots.get(1).calleeType());
    }

    @Test
    void exactTWrapsSomeWhileAlreadyTypedOptionPassesThrough() {
        List<Ast.Param> formals = List.of(
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"));
        for (boolean alreadyOption : List.of(false, true)) {
            Ast.Expr supplied = alreadyOption
                    ? new Ast.NameExpr("maybe") : new Ast.LiteralExpr("hi");
            List<Ast.CallArgument> call = List.of(Ast.CallArgument.value(supplied));
            var shape = OptionalCallShapeBinder.bindParsed(
                    OptionalCallShapeBinder.declaredParameterShapes(formals), call);
            var preview = OptionalCallAbiPreview.prepareExact(
                    formals, call, shape, List.of(alreadyOption ? OPTION_STRING : STRING));
            assertEquals(alreadyOption ? OptionalCallAbiPreview.Action.PASS_OPTION
                    : OptionalCallAbiPreview.Action.WRAP_SOME,
                    preview.abiSlots().getFirst().action());
            assertSame(supplied, preview.sourceExpressions().getFirst());
            assertEquals(OPTION_STRING, preview.abiSlots().getFirst().calleeType());
        }
    }

    @Test
    void explicitOptionNoneIsAValueButOmissionDoesNotEvaluateAnything() {
        List<Ast.Param> formals = List.of(
                new Ast.Param(Ast.TypeRef.omittableFormal(STRING), "label"));
        List<Ast.CallArgument> typedNone =
                List.of(Ast.CallArgument.value(new Ast.NameExpr("None")));
        var typedShape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals), typedNone);
        var typed = OptionalCallAbiPreview.prepareExact(
                formals, typedNone, typedShape, List.of(OPTION_STRING));
        assertEquals(OptionalCallAbiPreview.Action.PASS_OPTION,
                typed.abiSlots().getFirst().action());
        assertEquals(1, typed.sourceExpressions().size());

        List<Ast.CallArgument> explicit = List.of(Ast.CallArgument.omit(null));
        var explicitShape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals), explicit);
        var omitted = OptionalCallAbiPreview.prepareExact(
                formals, explicit, explicitShape, List.of());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                omitted.abiSlots().getFirst().action());
        assertEquals(0, omitted.abiSlots().getFirst().originalCallItemIndex());
        assertTrue(omitted.sourceExpressions().isEmpty());

        var implicitShape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals), List.of());
        var implicit = OptionalCallAbiPreview.prepareExact(
                formals, List.of(), implicitShape, List.of());
        assertEquals(-1, implicit.abiSlots().getFirst().originalCallItemIndex());
    }

    @Test
    void requiredOptionIsNotOmittableAndReceivesNoImplicitSome() {
        List<Ast.Param> formals = List.of(new Ast.Param(OPTION_STRING, "required"));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindParsed(
                        OptionalCallShapeBinder.declaredParameterShapes(formals), List.of()));
        List<Ast.CallArgument> call =
                List.of(Ast.CallArgument.value(new Ast.NameExpr("someOption")));
        var shape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals), call);
        assertEquals(OptionalCallAbiPreview.Action.PASS_REQUIRED,
                OptionalCallAbiPreview.prepareExact(formals, call, shape,
                        List.of(OPTION_STRING)).abiSlots().getFirst().action());
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(formals, call, shape, List.of(STRING)));
    }

    @Test
    void forgedSelectionMissingTypesAndUnresolvedTypesCannotPrepareAbi() {
        List<Ast.CallArgument> call = List.of(
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(1L)));
        var shapes = OptionalCallShapeBinder.declaredParameterShapes(formals());
        var genuine = OptionalCallShapeBinder.bindParsed(shapes, call);
        var forged = new OptionalCallShapeBinder.Plan(
                List.of(0, -1, 1), -1, List.of(), List.of(1), List.of(0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        formals(), call, forged, List.of(BOOL, INT)));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        formals(), call, genuine, List.of(BOOL)));
        for (Ast.TypeRef unproven : List.of(
                Ast.TypeRef.simple("void"), Ast.TypeRef.simple("Unknown"),
                Ast.TypeRef.inferred(), Ast.TypeRef.borrowed(INT, false),
                Ast.TypeRef.simple("null"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallAbiPreview.prepareExact(
                            formals(), call, genuine, List.of(BOOL, unproven)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        formals(), call, genuine, List.of(INT, BOOL)));
    }

    @Test
    void manuallyForgedAbiTablesCannotDuplicateDropOrRetypeSources() {
        Ast.Expr effect = new Ast.NameExpr("effect");
        var first = new OptionalCallAbiPreview.Slot(
                0, "first", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0);
        var duplicate = new OptionalCallAbiPreview.Slot(
                1, "second", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(
                        List.of(effect), List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(List.of(effect), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(
                        List.of(effect), List.of(new OptionalCallAbiPreview.Slot(
                                0, "x", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Slot(
                        0, "b", STRING, OptionalCallAbiPreview.Action.INJECT_NONE, -1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Slot(
                        0, "b", STRING, OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(
                        List.of(effect), List.of(first,
                                new OptionalCallAbiPreview.Slot(
                                        1, "optional", OPTION_STRING,
                                        OptionalCallAbiPreview.Action.INJECT_NONE, -1, 0))));
    }

    @Test
    void forgedPreviewCannotInventGapsOrReorderSourceOrigins() {
        Ast.Expr first = new Ast.NameExpr("firstEffect");
        Ast.Expr second = new Ast.NameExpr("secondEffect");
        var source0 = new OptionalCallAbiPreview.Slot(
                0, "a", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0);
        var source1Gap = new OptionalCallAbiPreview.Slot(
                1, "b", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 3);
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(
                        List.of(first, second), List.of(source0, source1Gap)));

        var source0Later = new OptionalCallAbiPreview.Slot(
                0, "a", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 1);
        var source1Earlier = new OptionalCallAbiPreview.Slot(
                1, "b", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallAbiPreview.Preview(
                        List.of(first, second), List.of(source0Later, source1Earlier)));

        // Declaration order may differ from lexical order, but raw source
        // origins must still increase when ordered by evaluated source index.
        var formal0FromSecond = new OptionalCallAbiPreview.Slot(
                0, "a", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 1);
        var formal1FromFirst = new OptionalCallAbiPreview.Slot(
                1, "b", INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0);
        assertDoesNotThrow(() -> new OptionalCallAbiPreview.Preview(
                List.of(first, second), List.of(formal0FromSecond, formal1FromFirst)));
    }

    @Test
    void sourceAndAbiTablesAreImmutableAndVariadicIsNotSilentlyLowered() {
        var source = List.of(Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("a", new Ast.LiteralExpr(3L)));
        var shape = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(formals()), source);
        var preview = OptionalCallAbiPreview.prepareExact(
                formals(), source, shape, List.of(BOOL, INT));
        assertThrows(UnsupportedOperationException.class,
                () -> preview.abiSlots().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> preview.sourceExpressions().clear());

        var restShapes = List.of(OptionalCallShapeBinder.Parameter.variadic("values"));
        var restSource = List.of(Ast.CallArgument.value(new Ast.LiteralExpr(5L)));
        var restPlan = OptionalCallShapeBinder.bindParsed(restShapes, restSource);
        assertEquals(0, restPlan.variadicParameterIndex());
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        List.of(new Ast.Param(INT, "values")), restSource, restPlan, List.of(INT)));
    }

    @Test
    void stagedCallPreviewEvaluatesCalleeBeforeNamedSourceOrder() {
        Ast.Expr receiver = new Ast.CallExpr(new Ast.NameExpr("getReceiver"), List.of());
        Ast.Expr callee = new Ast.MemberExpr(receiver, "work");
        Ast.Expr c = new Ast.CallExpr(new Ast.NameExpr("effectC"), List.of());
        Ast.Expr a = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        Ast.StagedCallExpr call = new Ast.StagedCallExpr(callee, List.of(), false, List.of(
                Ast.CallArgument.named("c", c),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", a)));
        var preview = OptionalCallAbiPreview.prepareStagedCallExact(
                call, formals(), List.of(BOOL, INT));
        assertEquals(OptionalCallAbiPreview.InvocationKind.CALL, preview.kind());
        assertSame(callee, preview.calleeExpression());
        assertNull(preview.constructedType());
        assertEquals(3, preview.evaluationOrder().size());
        assertSame(callee, preview.evaluationOrder().get(0));
        assertSame(c, preview.evaluationOrder().get(1));
        assertSame(a, preview.evaluationOrder().get(2));
        assertEquals(3, preview.arguments().fullArity());
        assertEquals(List.of(2, 1, 0),
                preview.arguments().abiSlots().stream()
                        .map(OptionalCallAbiPreview.Slot::originalCallItemIndex).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> preview.evaluationOrder().clear());
    }

    @Test
    void stagedConstructionAndActorSpawnHaveNoGuestCalleeButRetainSourceOrder() {
        Ast.Expr c = new Ast.NameExpr("third");
        Ast.Expr a = new Ast.NameExpr("first");
        List<Ast.CallArgument> source = List.of(
                Ast.CallArgument.named("c", c),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", a));
        for (boolean actor : List.of(false, true)) {
            Ast.StagedNewExpr construction =
                    new Ast.StagedNewExpr(Ast.TypeRef.simple("Worker"), source, actor);
            var preview = OptionalCallAbiPreview.prepareStagedConstructionExact(
                    construction, formals(), List.of(BOOL, INT));
            assertEquals(actor ? OptionalCallAbiPreview.InvocationKind.ACTOR_SPAWN
                               : OptionalCallAbiPreview.InvocationKind.CLASS_CONSTRUCTOR,
                    preview.kind());
            assertNull(preview.calleeExpression());
            assertEquals(construction.type(), preview.constructedType());
            assertEquals(2, preview.evaluationOrder().size());
            assertSame(c, preview.evaluationOrder().get(0));
            assertSame(a, preview.evaluationOrder().get(1));
            assertEquals(3, preview.arguments().fullArity());
        }
    }

    @Test
    void stagedInvocationPreviewFailsClosedOnUnprovenGenericAndSpread() {
        List<Ast.CallArgument> arguments = List.of(
                Ast.CallArgument.named("a", new Ast.LiteralExpr(1L)),
                Ast.CallArgument.named("c", new Ast.LiteralExpr(true)));
        var genericCall = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(INT), true, arguments);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareStagedCallExact(
                        genericCall, formals(), List.of(INT, BOOL)));
        var genericConstruction = new Ast.StagedNewExpr(
                new Ast.TypeRef("Box", List.of(INT), false), arguments, false);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareStagedConstructionExact(
                        genericConstruction, formals(), List.of(INT, BOOL)));

        var spreadCall = new Ast.StagedCallExpr(new Ast.NameExpr("f"),
                List.of(), false, List.of(
                        Ast.CallArgument.spread(new Ast.NameExpr("values")),
                        Ast.CallArgument.named("c", new Ast.LiteralExpr(true))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareStagedCallExact(
                        spreadCall, formals(), List.of(INT, BOOL)));
    }

    @Test
    void literalNullCannotBeLaunderedByClaimedCheckedType() {
        List<Ast.Param> required = List.of(new Ast.Param(INT, "a"));
        List<Ast.CallArgument> items = List.of(
                Ast.CallArgument.named("a", new Ast.LiteralExpr(null)));
        var plan = OptionalCallShapeBinder.bindParsed(
                OptionalCallShapeBinder.declaredParameterShapes(required), items);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareExact(
                        required, items, plan, List.of(INT)));
        var staged = new Ast.StagedCallExpr(
                new Ast.NameExpr("f"), List.of(), false, items);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallAbiPreview.prepareStagedCallExact(
                        staged, required, List.of(INT)));
    }

}
