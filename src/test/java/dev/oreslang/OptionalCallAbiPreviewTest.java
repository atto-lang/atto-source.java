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
}
