package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.FailureAnalysis;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FailureHardeningTest {
    @Test void uncheckedSuppressesOnlySignatureAssumptionsOnThatCall() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc promised(): void throws raises panics {}
                fnc normal(): void { promised(); }
                fnc opted_out(): void { unchecked promised(); }
                fnc mixed(): void { unchecked promised(); promised(); }
                """));
        assertTrue(has(diagnostics, "normal", "throws raises panics"));
        assertFalse(has(diagnostics, "opted_out", "may propagate"));
        assertTrue(has(diagnostics, "mixed", "throws raises panics"));
    }

    @Test void uncheckedRetainsKnownBodyEffectsAndArgumentEffects() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc failure(): void raises { raise "known"; }
                fnc promised_arg(): int panics { return 1; }
                fnc accept(int value): void throws {}
                fnc body(): void { unchecked failure(); }
                fnc arguments(): void { unchecked accept(promised_arg()); }
                fnc transitive(): void { unchecked body(); }
                """));
        assertTrue(has(diagnostics, "body", "raises"));
        assertTrue(has(diagnostics, "transitive", "raises"));
        assertTrue(has(diagnostics, "arguments", "panics"));
        assertFalse(has(diagnostics, "arguments", "throws"));
    }

    @Test void uncheckedCannotCertifyUnknownCalls() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc invoke(Fnc<void> callback): void { unchecked callback(); }
                """));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("failure-unknown")));
    }

    @Test void expressionTrapMasksOnlyOrdinaryThrows() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc failure(): int throws raises panics { return 0; }
                fnc use(): void { val result = trap failure(); }
                """));
        assertTrue(has(diagnostics, "use", "raises panics"));
        assertFalse(has(diagnostics, "use", "throws"));
    }

    @Test void uncheckedPreservesCallTypingAndMetadataThroughOptimization() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                fnc id(int n): int throws { print(n); return n; }
                pub fnc main(): int { return unchecked id(42); }
                """);
        var optimized = dev.oreslang.compiler.TreeShaker.shake(program, BuildOptions.executable());
        var main = (Ast.FunctionDecl) optimized.program().modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals("main")).findFirst().orElseThrow();
        var call = (Ast.CallExpr) ((Ast.ReturnStmt) main.body().getFirst()).value();
        assertTrue(call.uncheckedEffects());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "fnc value(int n): void {} fnc use(): void { unchecked value(true); }"));
        for (String expression : List.of("unchecked 1", "unchecked recover()", "unchecked unchecked value()")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc use(): void { " + expression + "; }"));
        }
    }

    @Test void uncheckedDoesNotCatchOrRecoverRuntimeFailures() {
        for (String operation : List.of("throw", "raise", "panic")) {
            String source = "fnc fail(): void { " + operation + " \"still-fails\"; } pub fnc main(): void { unchecked fail(); }";
            OresCompiler.parseAndTypeCheck(source);
            try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval("ores", source));
                assertTrue(failure.getMessage().contains("still-fails"), failure::getMessage);
            }
        }
    }

    @Test void deferredRecoverCannotHideFatalCleanupDuringSynchronousOrResumableUnwind() {
        for (boolean async : List.of(false, true)) {
            String source = """
                    fnc work(): void {
                      defer || -> { recover(); };
                      defer || -> { panic "cleanup-panic"; };
                      raise "original-raise";
                    }
                    pub fnc main(): void { work(); }
                    """;
            if (async) source = source.replace("fnc work()", "async fnc work()")
                    .replace("pub fnc main()", "pub async fnc main()")
                    .replace("{ work(); }", "{ await work(); }");
            OresCompiler.parseAndTypeCheck(source);
            try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                final String program = source;
                PolyglotException failure = assertThrows(PolyglotException.class, () -> context.eval("ores", program));
                assertTrue(failure.getMessage().contains("original-raise"), failure::getMessage);
            }
        }
    }

    @Test void recoverDoesNotInventSuccessValuesForNonVoidFunctionsMethodsOrLambdas() {
        for (String cleanup : List.of("defer || -> { recover(); };", "defer recover();", "defer (|| -> { recover(); })();")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                    "fnc value(): int { " + cleanup + " raise 1; return 2; }"));
            assertTrue(failure.getMessage().contains("recovery result"), failure::getMessage);
        }
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Example as
                  pub value(): int { defer recover(); raise 1; return 2; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc use(): void {
                  val Fnc<int> f = || -> { defer recover(); raise 1; return 2; };
                }
                """));
    }

    @Test void recoveredPayloadCannotMasqueradeAsAnUnrelatedStaticType() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc use(): void {
                  defer || -> { val int number = recover().unwrap(); };
                  raise "not-an-int";
                }
                """));
    }

    @Test void uncheckedDoesNotBypassMovesOrAsyncTyping() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Resource as pub val int id = 7; end
                fnc consume(Resource value): void throws {}
                fnc use(): void {
                  let Resource resource = new Resource();
                  unchecked consume(resource);
                  print(resource.id);
                }
                """));
        String source = """
                async fnc value(): int throws { return 42; }
                pub async fnc main(): int { return await unchecked value(); }
                """;
        OresCompiler.parseAndTypeCheck(source);
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            assertEquals(42L, context.eval("ores", source).asLong());
        }
    }

    @Test void overloadedEffectsNeverLeakBetweenAritiesAndShadowsStayUnknown() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc value(): void raises {}
                fnc value(int n): void panics {}
                fnc zero(): void { value(); }
                fnc one(): void { value(1); }
                fnc shadow(Fnc<void> value): void { value(); }
                """));
        assertTrue(has(diagnostics, "zero", "raises"));
        assertFalse(has(diagnostics, "zero", "panics"));
        assertTrue(has(diagnostics, "one", "panics"));
        assertFalse(has(diagnostics, "one", "raises"));
        assertTrue(diagnostics.stream().anyMatch(d -> d.symbol().endsWith(".shadow/1") && d.code().equals("failure-unknown")));
    }

    private static boolean has(List<FailureAnalysis.Diagnostic> diagnostics, String name, String message) {
        return diagnostics.stream().anyMatch(d -> d.symbol().endsWith("." + name + "/0") && d.message().contains(message));
    }
}
