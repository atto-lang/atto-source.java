package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual linked Oreslang source, not a synthetic Java host Behavior.
 * The guest Error stays on the failing actor's heap during on_throw.
 */
final class SourceActorOnThrowExecutionTest {
    @TempDir Path temp;

    private Path source(String effect, String handler) throws Exception {
        Files.writeString(temp.resolve("failure.ores"), """
                pub define class WorkerFault extends Error as
                    pub val int code;
                end
                """);
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import class WorkerFault from "./failure.ores";

                define actor Worker as
                    HANDLER
                    receive(ActorMail<String> mail): void {
                        EFFECT
                    }
                end

                pub async routine main(): void {
                    val worker = spawn Worker();
                    await worker.get_ready();
                    worker.send("go");
                    await worker.get_done();
                    return;
                }
                """.replace("HANDLER", handler).replace("EFFECT", effect));
        return entry;
    }

    private static String accepting() {
        return "on_throw(Error problem): bool { self.end(); return true; }";
    }

    private int run(Path path) throws Exception {
        return LinkedProgramRunner.runWithExitStatus(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()).exitStatus();
    }

    @Test void synchronousThrowHandledOnOriginalActor() throws Exception {
        Path entry = source("throw new WorkerFault(7);", accepting());
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertEquals(0, run(entry));
    }

    @Test void cooperativeLoopThrowInvokesOnThrowBeforeFutureEscapes() throws Exception {
        Path entry = source("loop { throw new WorkerFault(7); }", accepting());
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertEquals(0, run(entry));
    }

    @Test void lexicalCatchPrecedesOnThrowHook() throws Exception {
        Path entry = source(
                "try { throw new WorkerFault(7); } "
                        + "catch (e) { self.end(); return; }",
                "on_throw(Error problem): bool { panic new WorkerFault(8); return true; }");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertEquals(0, run(entry));
    }

    @Test void throwInsideOnThrowCannotExportAnotherGuestError() throws Exception {
        Path entry = source("throw new WorkerFault(7);",
                "on_throw(Error problem): bool { throw new WorkerFault(8); return false; }");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(entry));
        java.util.ArrayDeque<Throwable> queue = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Throwable, Boolean> seen = new java.util.IdentityHashMap<>();
        queue.add(failure);
        while (!queue.isEmpty()) {
            Throwable next = queue.removeFirst();
            assertTrue(seen.size() < 64, "host failure graph must remain bounded");
            if (seen.put(next, Boolean.TRUE) != null) continue;
            assertFalse(next.getClass().getName().contains("GuestThrowSignal"),
                    "guest Error leaked from on_throw into the host");
            if (next.getCause() != null) queue.add(next.getCause());
            for (Throwable suppressed : next.getSuppressed()) queue.add(suppressed);
        }
    }

    @Test void handlerDecliningFailureEscalatesToActorRaise() throws Exception {
        Path entry = source("throw new WorkerFault(7);",
                "on_throw(Error problem): bool { return false; }");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertThrows(RuntimeException.class, () -> run(entry));
    }

    @Test void raiseAndPanicCannotBeConsumedByOnThrow() throws Exception {
        for (String effect : new String[]{"raise", "panic"}) {
            Path entry = source(effect + " new WorkerFault(7);", accepting());
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
            assertThrows(RuntimeException.class, () -> run(entry));
        }
    }

    @Test void onThrowSignatureAndEffectsAreCompilerChecked() throws Exception {
        for (String invalid : new String[]{
                "on_throw(int error): bool { return true; }",
                "on_throw(Error error): void { return; }",
                "pub on_throw(Error error): bool { return true; }",
                "on_throw(Error error, Error other): bool { return true; }",
                "async on_throw(Error error): bool { return true; }"
        }) {
            Path entry = source("throw new WorkerFault(7);", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry), invalid);
        }
    }

    @Test void sourceCannotInvokeActorLifecycleHookDirectly() throws Exception {
        for (String invocation : new String[]{
                "self.on_throw(new WorkerFault(7));",
                "on_throw(new WorkerFault(7));",
                "val callback = self.on_throw;"
        }) {
            Path entry = source(invocation,
                    "on_throw(Error problem): bool { return true; }");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry), invocation);
        }
    }

    @Test void cooperativeOrLoopingHandlerFailsClosed() throws Exception {
        Path entry = source("throw new WorkerFault(7);",
                "on_throw(Error problem): bool { loop { throw new WorkerFault(8); } return true; }");
        // Accepted as an ordinary source method body, but not as an
        // executable actor failure handler: no suspend/continuation may
        // retain the guest Error past the local exceptional boundary.
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertThrows(RuntimeException.class, () -> run(entry));
    }

    @Test void sourceOnRaiseRemainsUnavailable() throws Exception {
        Path entry = source("throw new WorkerFault(7);",
                "on_raise(Error error): bool { return true; }");
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }
}
