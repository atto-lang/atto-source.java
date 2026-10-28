package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresDuration;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest source smoke for timer futures; does not certify Native Image AOT. */
final class SourceTimerLanguageTest {
    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, sourceText, "source-timer-smoke.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void asyncMainAwaitsSleepAndAfterWithoutExplicitTask() throws Exception {
        String source = """
                pub async fnc main(): void {
                    await time.sleep(0ms);
                    await time.after(1ms);
                    stdio.println(42);
                    return;
                }
                """;
        assertFalse(source.contains("task {"));
        assertTrue(run(source).contains("42"));
    }

    @Test
    void lexerParserPreserveExactNanosecondsWithoutFloatRounding() {
        var program = Parser.parse("""
                pub fnc value(): Duration {
                    return 1_500ms;
                }
                """);
        Ast.FunctionDecl function = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("value"))
                .findFirst().orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        Ast.LiteralExpr literal = assertInstanceOf(
                Ast.LiteralExpr.class, returned.value());
        OresDuration duration = assertInstanceOf(OresDuration.class, literal.value());
        assertEquals(1_500_000_000L, duration.nanos());
    }

    @Test
    void typedDurationAndFutureAreDistinctFromIntegers() throws Exception {
        String source = """
                pub async fnc main(): void {
                    val Duration delay = 1ms;
                    val Future<void> wake = time.after(delay);
                    await wake;
                    stdio.println(43);
                    return;
                }
                """;
        assertTrue(run(source).contains("43"));
    }

    @Test
    void durationCannotMasqueradeAsNumericPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc wrong(): int {
                            return 1ms;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc wrong(): Duration {
                            return 5;
                        }
                        """));
    }

    @Test
    void allSupportedDurationUnitsAreCheckedAndAwaitable() throws Exception {
        String source = """
                pub async fnc main(): void {
                    await time.after(0ns);
                    await time.sleep(0us);
                    await time.after(0ms);
                    await time.sleep(0s);
                    await time.after(0m);
                    await time.sleep(0h);
                    stdio.println(7);
                    return;
                }
                """;
        assertTrue(run(source).contains("7"));
    }

    @Test
    void rawIntegersAreNotSilentlyAcceptedAsDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                            await time.sleep(1);
                            return;
                        }
                        """));
    }

    @Test
    void syncCallableCannotAwaitTimerFuture() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc ordinary_helper(): void {
                            await time.after(1ms);
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("await requires"),
                "must reject the suspension effect, not an unrelated syntax error");
    }

    @Test
    void rootMainMayAwaitExistingTimerWithoutExplicitAsyncModifier() throws Exception {
        // The root entrypoint already has a scheduler Task. Do not create
        // another Task to bridge a timer or revoke legacy main semantics.
        String source = """
                pub routine main(): void {
                    await time.sleep(0ms);
                    stdio.println(44);
                    return;
                }
                """;
        assertTrue(run(source).contains("44"));
    }

    @Test
    void overflowAndUnsupportedDurationUnitsFailAtParseOrTypecheck() {
        for (String duration : new String[]{
                "9223372036854775807h", "1day", "1.5ms", "1__0ms", "-1ms"}) {
            String program = "pub async fnc main(): void { await time.sleep("
                    + duration + "); return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program),
                    "admitted invalid duration " + duration);
        }
    }

    @Test
    void builtinDurationAndTimeNamespaceCannotBeSpoofed() {
        for (String source : new String[]{
                "define class Duration as end",
                "define interface Duration as end",
                "define module time end"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    "spoofed reserved timer builtin: " + source);
        }
    }

    @Test
    void actorTimerRegistrationCannotBypassActorLifecycleAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub actor fnc unsafe_actor_timer(int n): void {
                            time.sleep(1ms);
                            return;
                        }
                        """));
    }

    @Test
    void unsupportedTimeoutAndCallbackAPIsAreFailClosed() {
        for (String call : new String[]{
                "time.timeout(1ms, time.after(2ms))",
                "time.set_timer(1ms, 42)",
                "time.interval(1ms)"}) {
            String source = "pub async fnc main(): void { " + call + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    "timer operation bypassed staged source admission: " + call);
        }
    }
}
