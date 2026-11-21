package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.LiteralNamedCallPreflight;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallTempLowering;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Narrow lexical provenance proof, NOT Java/GraalVM executable call tests. */
final class LiteralNamedCallPreflightTest {
    private static Ast.Program program(String formal, String actual, String callerParams) {
        return Parser.parse("define module app as\n"
                + "pub fnc work(" + formal + "): int { return a; }\n"
                + "pub fnc caller(" + callerParams + "): void {\n"
                + "  const result = work(" + actual + ");\n"
                + "  return;\n"
                + "}\nend");
    }

    private static Ast.Program simple() {
        return program("int a, bool c", "c: true, a: 7", "");
    }

    private static Ast.FunctionDecl caller(Ast.Program p) {
        return (Ast.FunctionDecl) p.modules().getFirst().declarations().get(1);
    }

    private static Ast.StagedCallExpr call(Ast.Program p) {
        return (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller(p).body().getFirst()).initializer();
    }

    private static LiteralNamedCallPreflight.Result inspect(Ast.Program p) {
        return LiteralNamedCallPreflight.inspect(
                p, p.modules().getFirst(), caller(p), call(p));
    }

    @Test
    void sourceOwnedExactArityNamedCallHasOneTempPerLexicalArgument() {
        Ast.Program program = simple();
        var proof = inspect(program);
        assertSame(program, proof.program());
        assertSame(program.modules().getFirst(), proof.module());
        assertSame(caller(program), proof.caller());
        assertSame(call(program), proof.call());
        assertSame(program.modules().getFirst().declarations().getFirst(),
                proof.bound().selectedDeclaration());
        assertEquals(OptionalCallTempLowering.TargetKind.STATIC_MODULE_FUNCTION,
                proof.recipe().targetKind());
        assertEquals(List.of(0, 1), proof.recipe().argumentTemps().stream()
                .map(OptionalCallTempLowering.ArgumentTemp::rawCallItemIndex).toList());
        assertEquals(List.of(1, 0), proof.recipe().abiSlots().stream()
                .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertEquals(2, proof.recipe().abiSlots().size());
        assertThrows(IllegalStateException.class, proof::requireExecutable);
        assertThrows(IllegalStateException.class, proof.recipe()::requireExecutable);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
    }

    @Test
    void syntacticOmissionCreatesTypedNoneButNoGuestTemp() {
        Ast.Program p = program("int a, string? b, bool c",
                "c: true, b: omit, a: 7", "");
        var proof = inspect(p);
        var slots = proof.recipe().abiSlots();
        assertEquals(3, slots.size());
        assertEquals(2, proof.recipe().argumentTemps().size());
        assertEquals(List.of(1, -1, 0), slots.stream()
                .map(OptionalCallTempLowering.AbiSlot::tempIndex).toList());
        assertEquals(OptionalCallAbiPreview.Action.INJECT_NONE, slots.get(1).action());
        assertEquals("Option", slots.get(1).calleeType().name());
        assertEquals(List.of(2, 1, 0), slots.stream()
                .map(OptionalCallTempLowering.AbiSlot::rawCallItemIndex).toList());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p),
                "optional formals must remain excluded from compiler execution");
    }

    @Test
    void sameValueReconstructedCallCannotImpersonateOriginalSourceOccurrence() {
        Ast.Program p = simple();
        Ast.StagedCallExpr original = call(p);
        Ast.StagedCallExpr forged = new Ast.StagedCallExpr(
                original.callee(), original.typeArguments(),
                original.typeArgumentsPresent(), original.arguments());
        assertNotSame(original, forged);
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(
                        p, p.modules().getFirst(), caller(p), forged));
    }

    @Test
    void callerAndModuleMustBelongToExactProgramIdentity() {
        Ast.Program p = simple();
        Ast.Program other = simple();
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(
                        p, other.modules().getFirst(), caller(other), call(other)));
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(
                        p, p.modules().getFirst(), caller(other), call(other)));
    }

    @Test
    void shadowedDirectCalleeCannotPretendToBeModuleFunction() {
        Ast.Program p = program("int a, bool c", "c: true, a: 7", "int work");
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void nonliteralArgumentsNeedRealLexicalCheckerProof() {
        Ast.Program p = program("int a, bool c", "c: true, a: value", "int value");
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void claimedExpectedTypeCannotRetypeBooleanAsInt() {
        Ast.Program p = program("int a, bool c", "c: true, a: false", "");
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void staticOwnerCannotGuessUnknownFunctionOrDynamicTarget() {
        Ast.Program p = simple();
        Ast.StagedCallExpr original = call(p);
        var dynamic = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("receiver"), "work"),
                List.of(), false, original.arguments());
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(
                        p, p.modules().getFirst(), caller(p), dynamic));
        Ast.Program wrongName = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        const result = unknown(c: true, a: 7);
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> inspect(wrongName));
    }

    @Test
    void sameNamedClassDeclarationCannotBypassNamespaceResolution() {
        Ast.Program p = simple();
        Ast.ModuleDecl original = p.modules().getFirst();
        Ast.ClassDecl conflicting = new Ast.ClassDecl(
                "work", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ModuleDecl collided = new Ast.ModuleDecl(
                original.name(), original.annotations(), original.contracts(),
                List.of(original.declarations().get(0), original.declarations().get(1),
                        conflicting));
        Ast.Program collisionProgram = new Ast.Program(List.of(collided));
        Ast.FunctionDecl caller = (Ast.FunctionDecl) collided.declarations().get(1);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(
                        collisionProgram, collided, caller, call));
    }

    @Test
    void positionalOmissionIsNotNamedOnlyPreflightEvidence() {
        Ast.Program p = program("int a, string? b, bool c",
                "7, omit, true", "");
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void namespacedProgramNeedsRealImportAndVisibilityAnalysis() {
        Ast.Program ordinary = simple();
        Ast.Program namespaced = new Ast.Program(
                "tenant", ordinary.imports(), ordinary.modules());
        assertThrows(IllegalArgumentException.class, () ->
                LiteralNamedCallPreflight.inspect(namespaced,
                        namespaced.modules().getFirst(), caller(namespaced), call(namespaced)));
    }

    @Test
    void optionalCallerParametersNeedFullCallableAbiProof() {
        Ast.Program p = program("int a, bool c",
                "c: true, a: 7", "int? unused");
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void localBindingMayNotShadowItsCalleeDuringPreflight() {
        Ast.Program p = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        const work = work(c: true, a: 7);
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void mutableFirstBindingRequiresIndependentLexicalLifetimeProof() {
        Ast.Program p = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        let result = work(c: true, a: 7);
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }

    @Test
    void mutableReferentEvenUnderConstNeedsOwnershipAndLoanChecking() {
        Ast.Program p = Parser.parse("""
                define module app as
                    pub fnc work(int a, bool c): int { return a; }
                    pub fnc caller(): void {
                        const mut result = work(c: true, a: 7);
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> inspect(p));
    }
}
