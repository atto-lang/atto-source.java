package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.LiteralOptionalCallCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experimental literal-only T? lowering must be admitted by the real
 * TypeChecker and OwnershipChecker, not by a shape-only witness.
 */
final class LiteralOptionalCallCompilerTest {
    private static String source(String call) {
        return """
                define module app as
                    pub fnc greet(string? name): string {
                        return match name over
                            on Some(n) -> n
                            on None -> "anonymous"
                        end
                    }
                    pub fnc caller(): string {
                        return %s;
                    }
                end
                """.formatted(call);
    }

    @Test
    void suppliedNamedScalarBecomesCheckedOrdinarySomeCall() {
        var lowered = LiteralOptionalCallCompiler.lowerAndAnalyze(
                source("greet(name: \"Alice\")"), "app", "caller");
        assertEquals(LiteralOptionalCallCompiler.SourceOrigin.ORIGINAL_STAGED_CALL,
                lowered.sourceOrigin());
        assertEquals(1, lowered.loweredCall().arguments().size());
        Ast.CallExpr some = assertInstanceOf(
                Ast.CallExpr.class, lowered.loweredCall().arguments().getFirst());
        assertEquals("Some", ((Ast.NameExpr) some.callee()).name());
        assertEquals("Alice", ((Ast.LiteralExpr) some.arguments().getFirst()).value());
        assertEquals("Option", lowered.checkedTarget().parameters().getFirst().type().name());
        assertEquals(Ast.TypeRef.simple("string"),
                lowered.checkedTarget().parameters().getFirst().type().arguments().getFirst());
        assertSame(((Ast.ReturnStmt) lowered.checkedCaller().body().getFirst()).value(),
                lowered.loweredCall());
        assertSame(lowered.checkedCaller(),
                lowered.checkedProgram().modules().getFirst().declarations().get(1));
    }

    @Test
    void explicitOmissionBecomesCheckedTypedNoneValue() {
        var lowered = LiteralOptionalCallCompiler.lowerAndAnalyze(
                source("greet(name: _)"), "app", "caller");
        Ast.NameExpr none = assertInstanceOf(
                Ast.NameExpr.class, lowered.loweredCall().arguments().getFirst());
        assertEquals("None", none.name());
        assertEquals(Ast.TypeRef.simple("string"),
                lowered.checkedTarget().parameters().getFirst().type().arguments().getFirst());
    }

    @Test
    void ordinaryCompilerContinuesToRejectUnloweredOptionalSource() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(source("greet(name: \"Alice\")"))));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(source("greet(name: _)"))));
    }

    @Test
    void wrongScalarAndUnverifiedOptionValuesCannotBeLowered() {
        for (String call : List.of(
                "greet(name: true)",
                "greet(name: 8)",
                "greet(name: None)",
                "greet(name: unknown)",
                "greet(name: Some(\"Alice\"))")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                            source(call), "app", "caller"), call);
        }
    }

    @Test
    void effectfulOrOrdinaryUnprovedCallsCannotUseLiteralOnlyLowering() {
        for (String call : List.of("greet(name: sideEffect())", "greet()")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                            source(call), "app", "caller"), call);
        }
    }

    @Test
    void optionalConstructorShadowingIsNotAuthorized() {
        String bad = """
                define module app as
                    pub fnc Some(string x): string { return x; }
                    pub fnc greet(string? name): string { return "ok"; }
                    pub fnc caller(): string { return greet(name: "Alice"); }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerAndAnalyze(bad, "app", "caller"));
    }

    @Test
    void checkedResultCannotBeConstructedOutsideTheFullCompiler() {
        assertEquals(0, LiteralOptionalCallCompiler.Result.class.getConstructors().length);
    }
    @Test
    void reordersRequiredAndOptionalScalarArgumentsAfterFullAdmission() {
        String source = """
                define module app as
                    pub fnc pick(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): int {
                        return pick(enabled: true, label: "Alice", a: 7);
                    }
                end
                """;
        var checked = LiteralOptionalCallCompiler.lowerAndAnalyze(source, "app", "caller");
        assertEquals(3, checked.loweredCall().arguments().size());
        assertEquals(7L, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(0)).value());
        var some = assertInstanceOf(Ast.CallExpr.class,
                checked.loweredCall().arguments().get(1));
        assertEquals("Some", ((Ast.NameExpr) some.callee()).name());
        assertEquals("Alice", ((Ast.LiteralExpr) some.arguments().getFirst()).value());
        assertEquals(true, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(2)).value());
    }

    @Test
    void omittedOptionalSlotDoesNotConsumeAnArgumentTemporary() {
        String source = """
                define module app as
                    pub fnc pick(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): int {
                        return pick(enabled: true, label: _, a: 7);
                    }
                end
                """;
        var checked = LiteralOptionalCallCompiler.lowerAndAnalyze(source, "app", "caller");
        assertEquals(3, checked.loweredCall().arguments().size());
        assertEquals("None", ((Ast.NameExpr) checked.loweredCall()
                .arguments().get(1)).name());
        assertEquals(7L, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(0)).value());
    }

    @Test
    void equivalentOrdinaryOptionProgramRunsUsingExistingGraalRuntime() throws Exception {
        // The default source admission still intentionally rejects T?
        // and StagedCallExpr. Prove the same compiled ABI values already
        // have matching OptionValue/Some/None runtime behavior.
        String ordinary = """
                define module app as
                    pub fnc greet(Option<string> name): string {
                        return match name over
                            on Some(n) -> n
                            on None -> "anonymous"
                        end
                    }
                end
                pub routine main(): void {
                    stdio.stdout.write(app.greet(Some("Alice")));
                    stdio.stdout.write("|");
                    stdio.stdout.write(app.greet(None));
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.analyze(Parser.parse(ordinary)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, ordinary,
                    "ordinary-option-abi.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("Alice|anonymous", output.toString(StandardCharsets.UTF_8));
    }


    @Test
    void optInImplicitEmptyCallUsesTheSameFullCheckedNoneAbi() {
        var checked = LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                source("greet()"), "app", "caller");
        assertEquals(LiteralOptionalCallCompiler.SourceOrigin.VERIFIED_IMPLICIT_EMPTY_CALL,
                checked.sourceOrigin());
        assertEquals(1, checked.loweredCall().arguments().size());
        Ast.NameExpr none = assertInstanceOf(
                Ast.NameExpr.class, checked.loweredCall().arguments().getFirst());
        assertEquals("None", none.name());
        assertEquals("Option", checked.checkedTarget().parameters().getFirst().type().name());
        assertSame(checked.loweredCall(),
                ((Ast.ReturnStmt) checked.checkedCaller().body().getFirst()).value());
        // Existing default experimental entry deliberately remains staged-only.
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                        source("greet()"), "app", "caller"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(source("greet()"))));
    }

    @Test
    void implicitEmptyCallFillsAllOptionalSlotsInDeclarationOrder() {
        String source = """
                define module app as
                    pub fnc combine(int? x, string? y, bool? flag): string {
                        return "ready";
                    }
                    pub fnc caller(): string { return combine(); }
                end
                """;
        var checked = LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                source, "app", "caller");
        assertEquals(3, checked.loweredCall().arguments().size());
        assertTrue(checked.loweredCall().arguments().stream().allMatch(
                value -> value instanceof Ast.NameExpr name && name.name().equals("None")));
        assertNotSame(checked.loweredCall().arguments().get(0),
                checked.loweredCall().arguments().get(1),
                "each omitted formal receives its own independent typed None site");
        assertEquals(List.of("int", "string", "bool"),
                checked.checkedTarget().parameters().stream()
                        .map(p -> p.type().arguments().getFirst().name()).toList());
    }

    @Test
    void implicitEmptyCallNeverInventsARequiredValueOrSelectsAnOverload() {
        for (String source : List.of(
                """
                define module app as
                    pub fnc f(int value, string? label): int { return value; }
                    pub fnc caller(): int { return f(); }
                end
                """,
                """
                define module app as
                    pub fnc f(): int { return 1; }
                    pub fnc caller(): int { return f(); }
                end
                """,
                """
                define module app as
                    pub fnc f(string? name): int { return 1; }
                    pub fnc f(int? value): int { return 2; }
                    pub fnc caller(): int { return f(); }
                end
                """)) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                            source, "app", "caller"));
        }
    }

    @Test
    void implicitEmptyCallRejectsGenericExplicitAndNonEmptyCalls() {
        for (String call : List.of(
                "greet(name: _)", "greet(name: \"Alice\")", "greet(\"Alice\")",
                "greet<string>()")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                            source(call), "app", "caller"), call);
        }
    }


    @Test
    void stagedNamedCallCannotDiscardOriginalOverloadIdentity() {
        // A named call can choose a candidate by its labels in source syntax.
        // Lowering discards those labels and changes optional formals to
        // Option<T>. Until the checked compiler retains a resolution witness
        // for the resulting positional call, a second same-name declaration
        // must make this experimental lowering fail closed.
        String overloaded = """
                define module app as
                    pub fnc greet(string? name): string { return "hello"; }
                    pub fnc greet(int first, int second, int third): string {
                        return "other";
                    }
                    pub fnc caller(): string {
                        return greet(name: "Alice");
                    }
                end
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                        overloaded, "app", "caller"));
        assertTrue(failure.getMessage().contains(
                        "post-lowering declaration resolution proof"),
                failure::toString);

        // The ordinary single-declaration, literal-only path stays opt-in
        // and full-checker admitted rather than being globally disabled.
        var nonOverloaded = LiteralOptionalCallCompiler.lowerAndAnalyze(
                source("greet(name: \"Alice\")"), "app", "caller");
        assertEquals(LiteralOptionalCallCompiler.SourceOrigin.ORIGINAL_STAGED_CALL,
                nonOverloaded.sourceOrigin());
        assertEquals(1, nonOverloaded.loweredCall().arguments().size());
    }

    @Test
    void implicitOmissionCannotCaptureAShadowedOptionConstructor() {
        String customNone = """
                define module app as
                    pub fnc None(): string { return "fake"; }
                    pub fnc greet(string? name): string { return "ok"; }
                    pub fnc caller(): string { return greet(); }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                        customNone, "app", "caller"));
        String localNone = """
                define module app as
                    pub fnc greet(string? name): string { return "ok"; }
                    pub fnc caller(): string {
                        const None = "fake";
                        return greet();
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                        localNone, "app", "caller"));
    }

    @Test
    void implicitOmissionCannotGuessBetweenMultipleImmediateCalls() {
        String ambiguous = """
                define module app as
                    pub fnc greet(string? name): string { return "ok"; }
                    pub fnc caller(): string {
                        greet();
                        return greet();
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(
                        ambiguous, "app", "caller"));
    }

}
