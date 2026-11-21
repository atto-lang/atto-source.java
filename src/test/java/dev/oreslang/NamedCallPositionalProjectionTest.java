package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.LiteralNamedCallPreflight;
import dev.oreslang.types.NamedCallPositionalProjection;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real TypeChecker/OwnershipChecker applied to a related positional AST, not staged execution. */
final class NamedCallPositionalProjectionTest {
    private static Ast.Program parse(String formals, String call, String workBody) {
        return Parser.parse("define module app as\n"
                + "pub fnc work(" + formals + "): int { " + workBody + " }\n"
                + "pub fnc caller(): void {\n"
                + "  const result = work(" + call + ");\n"
                + "  return;\n"
                + "}\nend");
    }

    private static LiteralNamedCallPreflight.Result preview(Ast.Program p) {
        Ast.ModuleDecl module = p.modules().getFirst();
        Ast.FunctionDecl caller = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        return LiteralNamedCallPreflight.inspect(p, module, caller, call);
    }

    @Test
    void realCheckerAcceptsExactNamedLiteralPermutationOnlyAsPositionalProjection() {
        Ast.Program original = parse("int a, bool c", "c: true, a: 7", "return a;");
        var preview = preview(original);
        var checked = assertDoesNotThrow(() -> NamedCallPositionalProjection.check(preview));
        assertSame(preview, checked.original());
        assertEquals(2, checked.checkedScalarFormalCount());
        assertEquals(List.of(1, 0), preview.recipe().abiSlots().stream()
                .map(dev.oreslang.types.OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertSame(preview.call(),
                ((Ast.BindingStmt) preview.caller().body().getFirst()).initializer());
        assertThrows(IllegalStateException.class, checked::requireExecutable);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(original),
                "original staged guest call remains blocked");
    }

    @Test
    void realCheckerRejectsInvalidCalleeBodyDespiteValidLiteralBindingShape() {
        Ast.Program original = parse("int a, bool c", "c: true, a: 7", "return unknown;");
        var preview = preview(original);
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(preview));
    }

    @Test
    void optionalNoneCannotBeSmuggledIntoLegacyPositionalCallAbi() {
        Ast.Program original = parse(
                "int a, string? label, bool c",
                "c: true, label: omit, a: 7", "return a;");
        var preview = preview(original);
        assertEquals(3, preview.recipe().abiSlots().size());
        assertEquals(2, preview.recipe().argumentTemps().size());
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(preview));
    }

    @Test
    void suppliedOptionalValueStillRequiresRealOptionAbiProof() {
        Ast.Program original = parse(
                "int a, bool? label, bool c",
                "c: true, label: false, a: 7", "return a;");
        var preview = preview(original);
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(preview));
    }

    @Test
    void unrelatedStagedCallInCallerStillFailsUnderRealChecker() {
        Ast.Program original = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        const first = work(c: true, a: 7);
                        const second = work(c: false, a: 8);
                        return;
                    }
                end
                """);
        var preview = preview(original);
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(preview));
    }

    @Test
    void missingOrMismatchedPrimitiveArgumentsAreRejectedBeforeProjection() {
        Ast.Program bad = parse("int a, bool c", "c: true, a: false", "return a;");
        assertThrows(IllegalArgumentException.class, () -> preview(bad));
    }

    @Test
    void noCallerProofCanBeFabricatedFromAReconstructedCall() {
        Ast.Program original = parse("int a, bool c", "c: true, a: 7", "return a;");
        Ast.ModuleDecl module = original.modules().getFirst();
        Ast.FunctionDecl caller = (Ast.FunctionDecl) module.declarations().get(1);
        var source = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        var forged = new Ast.StagedCallExpr(
                source.callee(), source.typeArguments(),
                source.typeArgumentsPresent(), source.arguments());
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(original, module, caller, forged));
    }

    @Test
    void receiptNeverExposesAnExecutableReplacementAst() {
        for (java.lang.reflect.Method method
                : NamedCallPositionalProjection.Result.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertNotEquals(Ast.Program.class, method.getReturnType(),
                        "projection must not expose a runnable positional program");
                assertNotEquals(Ast.CallExpr.class, method.getReturnType(),
                        "projection must not expose an executable ordinary call");
            }
        }
        Ast.Program p = parse("int a, bool c", "c: true, a: 7", "return a;");
        var receipt = assertDoesNotThrow(() -> NamedCallPositionalProjection.check(preview(p)));
        assertThrows(IllegalStateException.class, receipt::requireExecutable);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
    }

    @Test
    void unrelatedFunctionMustNotInfluenceProjectedCallTarget() {
        Ast.Program program = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc helper(int n): int { return n; }
                    pub fnc caller(): void {
                        const result = work(c: true, a: 7);
                        return;
                    }
                end
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl caller = (Ast.FunctionDecl) module.declarations().get(2);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        var inspected = LiteralNamedCallPreflight.inspect(program, module, caller, call);
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(inspected));
    }

    @Test
    void unrelatedClassMustNotBeInterpretedAsCheckedCallScope() {
        Ast.Program source = parse("int a, bool c", "c: true, a: 7", "return a;");
        Ast.ModuleDecl original = source.modules().getFirst();
        Ast.ClassDecl extra = new Ast.ClassDecl(
                "Unrelated", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ModuleDecl module = new Ast.ModuleDecl(
                original.name(), original.annotations(), original.contracts(),
                List.of(original.declarations().get(0),
                        original.declarations().get(1), extra));
        Ast.Program program = new Ast.Program(List.of(module));
        Ast.FunctionDecl caller = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        var inspected = LiteralNamedCallPreflight.inspect(program, module, caller, call);
        assertThrows(IllegalArgumentException.class,
                () -> NamedCallPositionalProjection.check(inspected));
    }

    @Test
    void everyThreeArgumentScalarPermutationRetainsOriginalFormalDestinations() {
        String[] items = {"a: 1", "b: 2", "c: 3"};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (i == j) continue;
                int k = 3 - i - j;
                String named = items[i] + ", " + items[j] + ", " + items[k];
                Ast.Program p = parse("int a, int b, int c", named, "return a;");
                var preview = preview(p);
                var receipt = assertDoesNotThrow(
                        () -> NamedCallPositionalProjection.check(preview), named);
                assertEquals(3, receipt.checkedScalarFormalCount());
                var slots = preview.recipe().abiSlots();
                assertEquals(List.of(0, 1, 2), slots.stream()
                        .map(dev.oreslang.types.OptionalCallTempLowering.AbiSlot::formalIndex)
                        .toList());
                assertEquals(List.of("a", "b", "c"), slots.stream()
                        .map(dev.oreslang.types.OptionalCallTempLowering.AbiSlot::name)
                        .toList());
                assertEquals(List.of(i, j, k), preview.recipe().argumentTemps().stream()
                        .map(temp -> slots.stream()
                                .filter(slot -> slot.tempIndex() == temp.tempIndex())
                                .findFirst().orElseThrow().formalIndex()).toList());
                assertThrows(IllegalStateException.class, receipt::requireExecutable);
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
            }
        }
    }
}
