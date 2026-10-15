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
 * Full linked-source actor execution (not a synthetic Java Behavior).
 * The Error class must be in an explicitly imported source unit; actors
 * must not gain ambient access to same-file classes.
 */
final class SourceActorTypedEffectExecutionTest {
    @TempDir Path temp;

    private Path source(String effect) throws Exception {
        Files.writeString(temp.resolve("failure.ores"), """
                pub define class WorkerFault extends Error as
                    pub val int code;
                end
                """);
        Path entry = temp.resolve("app-" + effect + ".ores");
        Files.writeString(entry, """
                import class WorkerFault from "./failure.ores";

                define actor Worker as
                    receive(ActorMail<String> mail): void {
                        EFFECT new WorkerFault(7);
                    }
                end

                pub async routine main(): void {
                    val worker = spawn Worker();
                    await worker.get_ready();
                    worker.send("go");
                    await worker.get_done();
                    return;
                }
                """.replace("EFFECT", effect));
        return entry;
    }

    @Test void raiseIsNotSwallowedByGuestCatchOrAsyncDone() throws Exception {
        Path entry = source("raise");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertThrows(RuntimeException.class, () -> LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test void panicIsTerminalEvenIfActorUsesGuestTryCatch() throws Exception {
        Path entry = source("panic");
        Files.writeString(entry, Files.readString(entry).replace(
                "panic new WorkerFault(7);",
                "try { panic new WorkerFault(7); } catch (e) { self.end(); return; }"));
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        assertThrows(RuntimeException.class, () -> LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test void uncaughtSynchronousActorThrowEscalatesToRaise() throws Exception {
        Path entry = source("throw");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        // Host runtime must see a terminal failure, never an owned guest
        // Error or a GuestThrowSignal that retains actor heap state.
        assertThrows(RuntimeException.class, () -> LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test void actorLocalTryCatchConsumesTheOriginalThrow() throws Exception {
        Path entry = source("throw");
        Files.writeString(entry, Files.readString(entry).replace(
                "throw new WorkerFault(7);",
                "try { throw new WorkerFault(7); } catch (e) { self.end(); return; }"));
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, result.exitStatus(), "locally handled throw must not raise");
    }

    @Test void actorThrowRequiresStaticallyProvenError() throws Exception {
        Path entry = source("throw");
        Files.writeString(entry, Files.readString(entry).replace(
                "throw new WorkerFault(7);", "throw 7;"));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }

    @Test void scalarActorEffectsAreRejectedBeforeRuntime() throws Exception {
        Path entry = source("raise");
        String source = Files.readString(entry).replace(
                "raise new WorkerFault(7);", "raise 7;");
        Files.writeString(entry, source);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
    }
}
