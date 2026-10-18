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
        assertNotSame(original, checked.checkedPositionalProgram());
        assertEquals(2, checked.projectedCall().arguments().size());
        assertSame(preview.call().arguments().get(1).value(),
                checked.projectedCall().arguments().get(0));
        assertSame(preview.call().arguments().get(0).value(),
                checked.projectedCall().arguments().get(1));
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
}
