package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallTempLowering;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-only temp/ABI recipes. Not guest execution or ownership proofs. */
final class OptionalCallTempLoweringTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");

    private static Ast.ModuleDecl module() {
        return Parser.parse("""
                define module app as
                    pub fnc work(int a, string? b, bool c): int { return a; }
                end
                """).modules().getFirst();
    }

    @Test
    void staticModuleHasNoGuestTargetTempAndOmissionsNeverEvaluate() {
        Ast.Expr sideC = new Ast.CallExpr(new Ast.NameExpr("effectC"), List.of());
        Ast.Expr sideA = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("work"),
                List.of(), false, List.of(
                Ast.CallArgument.named("c", sideC),
                Ast.CallArgument.omit("b"),
                Ast.CallArgument.named("a", sideA)));
        var bound = BoundInvocation.bindModuleFunction(module(), call, List.of(BOOL, INT));
        var recipe = OptionalCallTempLowering.from(bound);

        assertEquals(OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION,
                recipe.targetKind());
        assertNull(recipe.receiverExpression());
        assertEquals(-1, recipe.receiverTempIndex());
        assertEquals(List.of(0, 1),
                recipe.argumentTemps().stream()
                        .map(OptionalCallTempLowering.ArgumentTemp::tempIndex).toList());
        assertEquals(List.of(0, 2),
                recipe.argumentTemps().stream()
                        .map(OptionalCallTempLowering.ArgumentTemp::rawCallItemIndex).toList());
        assertSame(sideC, recipe.argumentTemps().get(0).expression());
        assertSame(sideA, recipe.argumentTemps().get(1).expression());
        assertEquals(List.of(1, -1, 0),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertEquals(List.of(2, 1, 0),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::rawCallItemIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE,
                recipe.abiSlots().get(1).action());
        assertEquals(new Ast.TypeRef("Option", List.of(STRING), false),
                recipe.abiSlots().get(1).calleeType());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, recipe.readiness());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
        assertThrows(UnsupportedOperationException.class, () -> recipe.abiSlots().clear());
        assertThrows(UnsupportedOperationException.class, () -> recipe.argumentTemps().clear());
    }

    @Test
    void instanceMethodCapturesReceiverBeforeEachSourceTemp() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        Ast.Expr receiver = new Ast.CallExpr(new Ast.NameExpr("resolveBox"), List.of());
        Ast.Expr sideC = new Ast.CallExpr(new Ast.NameExpr("sideC"), List.of());
        Ast.Expr sideA = new Ast.CallExpr(new Ast.NameExpr("sideA"), List.of());
        var call = new Ast.StagedCallExpr(new Ast.MemberExpr(receiver, "work"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("c", sideC),
                    Ast.CallArgument.named("a", sideA)));
        var bound = BoundInvocation.bindClassMethod(klass, call, List.of(BOOL, INT));
        var recipe = OptionalCallTempLowering.from(bound);

        assertEquals(OptionalCallTempLowering.TargetKind.INSTANCE_METHOD_RECEIVER,
                recipe.targetKind());
        assertSame(receiver, recipe.receiverExpression());
        assertEquals(0, recipe.receiverTempIndex());
        assertEquals(List.of(1, 2),
                recipe.argumentTemps().stream()
                        .map(OptionalCallTempLowering.ArgumentTemp::tempIndex).toList());
        assertEquals(List.of(2, 1),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertEquals(List.of(1, 0),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::rawCallItemIndex).toList());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void constructorAllocatesOnlyArgumentTempsAndNoReceiver() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int x, bool y) { return; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        var call = new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), List.of(
                Ast.CallArgument.named("y", new Ast.LiteralExpr(true)),
                Ast.CallArgument.named("x", new Ast.LiteralExpr(7L))), false);
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindLiteralClassConstructor(klass, call));
        assertEquals(OptionalCallTempLowering.TargetKind.CLASS_CONSTRUCTOR,
                recipe.targetKind());
        assertNull(recipe.receiverExpression());
        assertEquals(-1, recipe.receiverTempIndex());
        assertEquals(List.of(0, 1),
                recipe.argumentTemps().stream()
                        .map(OptionalCallTempLowering.ArgumentTemp::tempIndex).toList());
        assertEquals(List.of(1, 0),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void repeatedAstObjectAtDifferentCallItemsHasTwoDistinctSourceTemps() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc dup(int a, int b): int { return a; }
                end
                """).modules().getFirst();
        Ast.Expr shared = new Ast.CallExpr(new Ast.NameExpr("effect"), List.of());
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("dup"),
                List.of(), false, List.of(
                Ast.CallArgument.named("b", shared),
                Ast.CallArgument.named("a", shared)));
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindModuleFunction(module, call, List.of(INT, INT)));
        assertSame(shared, recipe.argumentTemps().get(0).expression());
        assertSame(shared, recipe.argumentTemps().get(1).expression());
        assertNotEquals(recipe.argumentTemps().get(0).tempIndex(),
                recipe.argumentTemps().get(1).tempIndex());
        assertEquals(List.of(1, 0),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void suppliedPrimitiveOptionalWrapsSomeButRequiredOptionNeverDoes() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc opt(int? a, Option<int> b): int { return 1; }
                end
                """).modules().getFirst();
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("opt"),
                List.of(), false, List.of(
                Ast.CallArgument.named("a", new Ast.LiteralExpr(4L)),
                Ast.CallArgument.named("b", new Ast.NameExpr("valueOfOption"))));
        var optionInt = new Ast.TypeRef("Option", List.of(INT), false);
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindModuleFunction(module, call, List.of(INT, optionInt)));
        assertEquals(List.of(OptionalCallAbiPreview.Action.WRAP_SOME,
                OptionalCallAbiPreview.Action.PASS_REQUIRED),
                recipe.abiSlots().stream()
                        .map(OptionalCallTempLowering.AbiSlot::action).toList());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void suppliedOptionToOmittableFormalPassesThroughWithoutDoubleWrapping() {
        Ast.ModuleDecl module = Parser.parse("""
                define module app as
                    pub fnc opt(int? value): int { return 1; }
                end
                """).modules().getFirst();
        var optionInt = new Ast.TypeRef("Option", List.of(INT), false);
        var call = new Ast.StagedCallExpr(new Ast.NameExpr("opt"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("value",
                                new Ast.NameExpr("alreadyOptional"))));
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindModuleFunction(module, call, List.of(optionInt)));
        assertEquals(OptionalCallAbiPreview.Action.PASS_OPTION,
                recipe.abiSlots().getFirst().action());
        assertEquals(optionInt, recipe.abiSlots().getFirst().calleeType());
        assertEquals(0, recipe.abiSlots().getFirst().tempIndex());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void explicitTemporaryPhasesSkipGuestStaticCalleeAndMaterializeAllFormalsLast() {
        Ast.Expr sideC = new Ast.CallExpr(new Ast.NameExpr("effectC"), List.of());
        Ast.Expr sideA = new Ast.CallExpr(new Ast.NameExpr("effectA"), List.of());
        var staged = new Ast.StagedCallExpr(new Ast.NameExpr("work"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("c", sideC),
                    Ast.CallArgument.omit("b"),
                    Ast.CallArgument.named("a", sideA)));
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindModuleFunction(module(), staged, List.of(BOOL, INT)));
        var phases = recipe.steps().stream().map(OptionalCallTempLowering.Step::phase).toList();
        assertEquals(List.of(
                OptionalCallTempLowering.Phase.CAPTURE_ARGUMENT,
                OptionalCallTempLowering.Phase.CAPTURE_ARGUMENT,
                OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT,
                OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT,
                OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT,
                OptionalCallTempLowering.Phase.INVOKE_SELECTED), phases);
        assertEquals(List.of(0, 1, 0, 1, 2, -1),
                recipe.steps().stream().map(OptionalCallTempLowering.Step::index).toList());
        assertThrows(UnsupportedOperationException.class, () -> recipe.steps().clear());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void instanceReceiverIsOnlyFirstCaptureAndNeverCallableCalleeTemp() {
        Ast.ClassDecl box = (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        pub fnc work(int a, bool c): int { return a; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
        var source = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "work"),
                List.of(), false, List.of(
                    Ast.CallArgument.named("c", new Ast.LiteralExpr(true)),
                    Ast.CallArgument.named("a", new Ast.LiteralExpr(8L))));
        var recipe = OptionalCallTempLowering.from(
                BoundInvocation.bindLiteralClassMethod(box, source));
        assertEquals(List.of(
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.CAPTURE_RECEIVER, 0),
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.CAPTURE_ARGUMENT, 1),
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.CAPTURE_ARGUMENT, 2),
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT, 0),
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT, 1),
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.INVOKE_SELECTED, -1)),
                recipe.steps());
        assertThrows(IllegalStateException.class, recipe::requireExecutable);
    }

    @Test
    void forgedPhaseIndexesAreRejectedBeforeBackendLowering() {
        assertThrows(IllegalArgumentException.class, () ->
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.CAPTURE_RECEIVER, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.CAPTURE_ARGUMENT, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.MATERIALIZE_ABI_SLOT, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new OptionalCallTempLowering.Step(OptionalCallTempLowering.Phase.INVOKE_SELECTED, 0));
        assertThrows(NullPointerException.class, () ->
                new OptionalCallTempLowering.Step(null, 0));
    }
}
