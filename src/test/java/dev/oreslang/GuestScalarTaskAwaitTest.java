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
        assertDoesNotThrow(() -> Parser.parse(program),
                "negative compiler fixture must be syntactically valid");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    private static void rejectsWithDiagnostic(String body, String message) {
        String program = "pub fnc main(): void { " + body + " return; }";
        assertDoesNotThrow(() -> Parser.parse(program),
                "negative compiler fixture must be syntactically valid");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
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
    void awaitedFloatAndLocalStringAreCopiedBeforeScheduling() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                    let float ratio = 2.5;
                    let string label = "scalar";
                    let float a = await task { return ratio; };
                    let string b = await task { return label; };
                    stdio.stdout.write(a);
                    stdio.stdout.write(b);
                    stdio.stdout.write(label);
                    return;
                }
                """);
        assertTrue(output.contains("2.5scalarscalar"), output);
    }

    @Test
    void directExportedMainInvocationCannotReuseRootLaunchProof() throws Exception {
        String program = """
                pub fnc main(): void {
                    let int x = 41;
                    let int result = await task { return x; };
                    stdio.stdout.write(result);
                    return;
                }
                """;
        OresCompiler.parseAndTypeCheck(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "root-task-once.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            // First run is the VM-owned main entrypoint, which mints one
            // root-Task launch proof. A later named export call does not.
            var executable = context.parse(source);
            executable.execute();
            assertTrue(output.toString().contains("41"), output::toString);
            RuntimeException denied = assertThrows(RuntimeException.class,
                    () -> executable.execute(
                            dev.oreslang.nodes.OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                            "main"));
            assertTrue(denied.toString().contains("root main"),
                    denied::toString);
        }
    }

    @Test
    void standaloneAndEscapingTaskHandlesStayRejected() {
        rejects("let Task<int> work = task { return 7; };");
        rejects("do task { return; };");
        rejects("let Task<int> work = task { return 2; }; await work;");
    }

    @Test
    void nestedTasksGuestCallsAndNonScalarBodiesStayRejected() {
        rejectsWithDiagnostic("let int n = await task { return task { return 1; }; };",
                "one return of a scalar literal or copied local name");
        rejects("let int n = await task { let int value = 1; return value; };");
        rejectsWithDiagnostic("let int n = await task { return 3 + 4; };",
                "one return of a scalar literal or copied local name");
        rejects("let int n = await task { return; };");
        rejectsWithDiagnostic("let data = arr[1, 2]; let int n = await task { return data; };",
                "proven copyable");
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
    void deferredCleanupCannotInheritRootTaskAdmission() {
        String source = """
                pub fnc main(): void {
                    defer await task { return 7; };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(denied.getMessage().contains("root main")
                        || denied.getMessage().contains("await requires"),
                denied::getMessage);
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
