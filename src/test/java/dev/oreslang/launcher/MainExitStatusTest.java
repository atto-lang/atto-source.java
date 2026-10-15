package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class MainExitStatusTest {
    @TempDir Path temp;

    private int exit(String source) throws Exception {
        Path path = temp.resolve("main.ores");
        Files.writeString(path, source);
        return LinkedProgramRunner.runWithExitStatus(path, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).exitStatus();
    }

    @Test void voidMainImpliesSuccess() throws Exception {
        assertEquals(0, exit("pub routine main(): void { return; }"));
        assertEquals(0, exit("pub routine main(): void { }"));
    }

    @Test void cliReturnsGuestStatusToShellBoundary() throws Exception {
        Path path = temp.resolve("cli-main.ores");
        Files.writeString(path, "pub routine main(): int { return 42; }");
        assertEquals(42, OresMain.exitStatusForArgs(new String[] {path.toString()}));
        Files.writeString(path, "pub routine main(): void { }");
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {path.toString()}));
    }

    @Test void cliSeparatesUsageErrorsFromCompilationErrors() throws Exception {
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--invalid-option"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--define="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--allow-host-class="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--allow="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--allow=PROCESS_EXIT,"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--allow=,PROCESS_EXIT"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "--check", "--build-analysis", temp.resolve("missing.ores").toString()
        }), "mutually exclusive options are usage errors even if the input file is missing");
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {new String(new char[] {0}) + "invalid.ores"}),
                "an invalid path is a malformed invocation, not a compilation failure");
        Path path = temp.resolve("bad.ores");
        Files.writeString(path, "pub routine main(): int { return missing_variable; }");
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {path.toString()}));
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {"--check", path.toString()}));
        Files.writeString(path, "pub routine helper(): void { }");
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {path.toString()}),
                "a missing main must never produce a successful process status");
    }

    @Test void malformedDenoPermissionListsAreUsageErrors() {
        for (String category : new String[]{
                "read", "write", "net", "env", "run", "sys", "ffi", "import"
        }) {
            for (String action : new String[]{"allow", "deny"}) {
                for (String scopes : new String[]{",", "alpha,", ",alpha", "alpha, ,beta"}) {
                    String flag = "--" + action + "-" + category + "=" + scopes;
                    assertEquals(2, OresMain.exitStatusForArgs(new String[]{flag}), flag);
                }
            }
        }
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"-not-an-option"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"-R="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"-W="}));
    }

    @Test void defaultCliGuestIoIsDeniedAndPermissionAliasesWork() throws Exception {
        Path source = temp.resolve("cli-permissions.ores");
        Files.writeString(source, """
                pub routine main(): void {
                    val path = env.get("PATH");
                    return;
                }
                """);
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{source.toString()}));
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--allow-env=PATH", source.toString()
        }));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--allow-env", "--deny-env=PATH", source.toString()
        }));
        Files.writeString(source, "pub routine main(): void { return; }");
        for (String flag : new String[]{"-A", "-R", "-W", "-R=.", "-W=."}) {
            assertEquals(0, OresMain.exitStatusForArgs(new String[]{flag, source.toString()}), flag);
        }
    }

    @Test void integerMainReturnsZero() throws Exception {
        assertEquals(0, exit("pub routine main(): int { return 0; }"));
    }

    @Test void integerMainReturnsFailureCode() throws Exception {
        assertEquals(1, exit("pub routine main(): int { return 1; }"));
        assertEquals(42, exit("pub routine main(): int { return 42; }"));
        assertEquals(255, exit("pub routine main(): int { return 255; }"));
    }

    @Test void asynchronousIntegerMainYieldsExitStatus() throws Exception {
        assertEquals(8, exit("pub async routine main(): int { return 8; }"));
    }

    @Test void explicitProcessExitYieldsHostStatus() throws Exception {
        assertEquals(7, exit("pub routine main(): void { std.process.exit(7); }"));
    }

    @Test void explicitProcessExitDoesNotRequireIntegerMain() throws Exception {
        assertEquals(2, exit("pub routine main(): void { process.exit(2); }"));
    }

    @Test void firstExplicitExitWinsAcrossDeferredExit() throws Exception {
        assertEquals(7, exit("""
                pub routine main(): void {
                    defer std.process.exit(5);
                    std.process.exit(7);
                }
                """));
    }

    @Test void deferredExitCannotHideEarlierUnhandledRootFailure() throws Exception {
        String failingRoutine = """
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                """;
        for (int requestedStatus : new int[] {0, 7}) {
            assertThrows(RuntimeException.class, () -> exit(failingRoutine + """
                    pub routine main(): void {
                        defer std.process.exit(%d);
                        fail();
                    }
                    """.formatted(requestedStatus)),
                    "an unhandled failure must remain authoritative over deferred process.exit("
                            + requestedStatus + ")");
        }
    }

    @Test void earlierExplicitExitSurvivesLaterFailedDeferredCleanup() throws Exception {
        assertEquals(7, exit("""
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                pub routine main(): void {
                    defer fail();
                    std.process.exit(7);
                }
                """));
    }

    @Test void deferCanRequestExitOnNormalVoidReturn() throws Exception {
        assertEquals(9, exit("""
                pub routine main(): void {
                    defer std.process.exit(9);
                }
                """));
    }

    @Test void explicitProcessExitInsideAsyncMainIsNotProcessGlobal() throws Exception {
        assertEquals(13, exit("pub async routine main(): void { std.process.exit(13); }"));
    }

    @Test void asyncDeferredExitCannotHideEarlierUnhandledRootFailure() throws Exception {
        String failingRoutine = """
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                """;
        for (int requestedStatus : new int[] {0, 7}) {
            assertThrows(RuntimeException.class, () -> exit(failingRoutine + """
                    pub async routine main(): void {
                        defer std.process.exit(%d);
                        fail();
                    }
                    """.formatted(requestedStatus)),
                    "async root failure must remain authoritative over deferred exit("
                            + requestedStatus + ")");
        }
    }

    @Test void asyncFirstExplicitExitWinsOverLaterDeferredFailure() throws Exception {
        assertEquals(7, exit("""
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                pub async routine main(): void {
                    defer fail();
                    std.process.exit(7);
                }
                """));
    }

    @Test void asyncFirstExplicitExitWinsOverLaterDeferredExit() throws Exception {
        assertEquals(7, exit("""
                pub async routine main(): void {
                    defer std.process.exit(5);
                    std.process.exit(7);
                }
                """));
    }

    @Test void synchronousTryCatchCannotInterceptExitOrReplaceItFromFinally() throws Exception {
        assertEquals(7, exit("""
                pub routine main(): void {
                    try { std.process.exit(7); }
                    catch (err) { std.process.exit(3); }
                    finally { std.process.exit(5); }
                }
                """));
    }

    @Test void synchronousFinallyExitCannotConvertCaughtFailureToSuccess() throws Exception {
        assertThrows(RuntimeException.class, () -> exit("""
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                pub routine main(): void {
                    try { fail(); }
                    catch (err) { fail(); }
                    finally { std.process.exit(0); }
                }
                """));
    }

    @Test void suspendableTryCatchCannotInterceptExitOrReplaceItFromFinally() throws Exception {
        assertEquals(7, exit("""
                async fnc step(): void { return; }
                pub async routine main(): void {
                    try {
                        await step();
                        std.process.exit(7);
                    }
                    catch (err) { std.process.exit(3); }
                    finally { std.process.exit(5); }
                }
                """));
    }

    @Test void suspendableFinallyExitCannotConvertEarlierFailureToSuccess() throws Exception {
        assertThrows(RuntimeException.class, () -> exit("""
                async fnc step(): void { return; }
                fnc fail(): void {
                    val Array<int> empty = new Array<int>();
                    empty.get(0);
                    return;
                }
                pub async routine main(): void {
                    try {
                        await step();
                        fail();
                    }
                    catch (err) { fail(); }
                    finally { std.process.exit(0); }
                }
                """));
    }

    @Test void tooLargeIntegerExitFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> exit("pub routine main(): int { return 256; }"));
    }

    @Test void outOfRangeExplicitProcessExitIsRejected() throws Exception {
        assertThrows(RuntimeException.class,
            () -> exit("pub routine main(): void { std.process.exit(256); }"));
    }

    @Test void unsupportedLinkedMainReturnValueIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> exit("pub routine main(): String { return \"invalid\"; }"));
    }

    @Test void untrustedIsolateCannotForceProcessTermination() throws Exception {
        Path path = temp.resolve("untrusted.ores");
        Files.writeString(path, "pub routine main(): void { std.process.exit(3); }");
        assertThrows(RuntimeException.class, () ->
            LinkedProgramRunner.runWithExitStatus(path,
                    IsolatePolicy.developer().withoutCapabilities(
                            IsolatePolicy.Capability.PROCESS_EXIT),
                    ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                    new ByteArrayOutputStream()));
    }
}
