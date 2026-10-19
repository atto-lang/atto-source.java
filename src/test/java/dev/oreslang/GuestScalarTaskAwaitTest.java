package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real .ores compiler -> continuation -> scheduler -> awaited scalar tests. */
final class GuestScalarTaskAwaitTest {
    private static String execute(String program) throws Exception {
        OresCompiler.parseAndTypeCheck(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, program, "guest-scalar-task.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString();
    }

    private static void rejects(String body) {
        String program = "pub fnc main(): void { " + body + " return; }";
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void awaitedLocalIntCopiesCaptureAndPreservesParentBinding() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                    let int x = 41;
                    let int answer = await task { return x; };
                    stdio.stdout.write(answer);
                    stdio.stdout.write(x);
                    return;
                }
                """);
        assertTrue(output.contains("4141"), output);
    }

    @Test
    void awaitedScalarLiteralsAreExecutedThroughRealGuestContext() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                    let int i = await task { return 42; };
                    let bool b = await task { return true; };
                    let string s = await task { return "ok"; };
                    stdio.stdout.write(i);
                    stdio.stdout.write(b);
                    stdio.stdout.write(s);
                    return;
                }
                """);
        assertTrue(output.contains("42trueok"), output);
    }

    @Test
    void legacyNbAliasRetainsTheSameScalarGuestSemantics() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                    let int result = await nb { return 43; };
                    stdio.stdout.write(result);
                    return;
                }
                """);
        assertTrue(output.contains("43"), output);
    }

    @Test
    void redundantLegacyNbTaskAliasDoesNotCreateNestedTasks() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                    let int result = await nb task { return 44; };
                    stdio.stdout.write(result);
                    return;
                }
                """);
        assertTrue(output.contains("44"), output);
    }

    @Test
    void standaloneAndEscapingTaskHandlesStayRejected() {
        rejects("let Task<int> work = task { return 7; };");
        rejects("do task { return; };");
        rejects("let Task<int> work = task { return 2; }; await work;");
    }

    @Test
    void nestedTasksGuestCallsAndNonScalarBodiesStayRejected() {
        rejects("let int n = await task { return task { return 1; }; };");
        rejects("let int n = await task { let int value = 1; return value; };");
        rejects("let int n = await task { return 3 + 4; };");
        rejects("let int n = await task { return; };");
        rejects("let data = arr[1, 2]; let int n = await task { return data; };");
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc sideEffect(): int { return 5; }
                pub fnc main(): void {
                    let int n = await task { return sideEffect(); };
                    return;
                }
                """));
    }

    @Test
    void nonRootGuestCallableCannotLaunchScalarTask() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                suspends fnc worker(): int { return await task { return 1; }; }
                pub fnc main(): void {
                    let int result = worker();
                    return;
                }
                """));
    }

    @Test
    void nonLocalNamesDoNotObtainRootScalarCaptureAuthority() {
        // A runtime facade or imported binding cannot be treated as an
        // independently copied scalar merely because its name resolves.
        rejects("let int answer = await task { return stdio; };");
        rejects("let int answer = await task { return print; };");
        String unknown = """
                pub fnc main(): void {
                    let int answer = await task { return missingLocal; };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(unknown));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(unknown));
    }

    @Test
    void nestedPlainLambdaMustNotInheritRootTaskAdmission() {
        String source = """
                pub fnc main(): void {
                    let int x = 4;
                    let handler = |int input| -> {
                        let int result = await task { return input; };
                        return result;
                    };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void asyncEntrypointCannotMintRootScopedScalarTask() {
        String source = """
                pub async fnc main(): void {
                    let int value = await task { return 7; };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void borrowedValuesAndHostCapabilitiesAreNotScalarCaptures() {
        rejects("let data = arr[1, 2]; let borrowed = rt borrow data; let int n = await task { return borrowed; };");
        rejects("let data = arr[1, 2]; let result = await task { return data; };");
    }
}
