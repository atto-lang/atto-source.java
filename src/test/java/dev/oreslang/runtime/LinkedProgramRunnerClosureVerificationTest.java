package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for trace preflight-to-compiler source drift. */
final class LinkedProgramRunnerClosureVerificationTest {
    @TempDir Path directory;

    @Test
    void rejectsMutatedTransitiveSourceBeforeExecutingGuest() throws Exception {
        Path entry = directory.resolve("main.ores");
        Path helper = directory.resolve("helper.ores");
        Files.writeString(helper, "pub fnc helper(): int { return 1; }\n");
        Files.writeString(entry,
                "import fnc helper from './helper';\n"
                + "pub routine main(): void { return; }\n");

        String original = LinkedProgramRunner.sourceClosureSha256(entry, Map.of());
        Files.writeString(helper, "pub fnc helper(): int { return 2; }\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        IOException rejected = assertThrows(IOException.class,
                () -> LinkedProgramRunner.runWithExitStatusVerified(
                        entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        original, out, err));
        assertTrue(rejected.getMessage().contains("source closure changed"),
                rejected.getMessage());
        assertEquals(0, out.size(), "no guest code should execute");
        assertEquals(0, err.size(), "no guest code should execute");
    }

    @Test
    void rejectsMutatedEntrypointBeforeExecutingGuest() throws Exception {
        Path entry = directory.resolve("mutable.ores");
        Files.writeString(entry, "pub routine main(): void { return; }\n");
        String original = LinkedProgramRunner.sourceClosureSha256(entry, Map.of());
        Files.writeString(entry, "pub routine main(): void { return; } // changed\n");

        IOException rejected = assertThrows(IOException.class,
                () -> LinkedProgramRunner.runWithExitStatusVerified(
                        entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        original, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(rejected.getMessage().contains("source closure changed"));
    }

    @Test
    void unchangedCompiledClosureExecutesNormally() throws Exception {
        Path entry = directory.resolve("unchanged.ores");
        Files.writeString(entry, "pub routine main(): void { return; }\n");
        String fingerprint = LinkedProgramRunner.sourceClosureSha256(entry, Map.of());
        var result = LinkedProgramRunner.runWithExitStatusVerified(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                fingerprint, new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, result.exitStatus());
    }

    @Test
    void rejectsMissingOrMalformedPreflightDigest() throws Exception {
        Path entry = directory.resolve("simple.ores");
        Files.writeString(entry, "pub routine main(): void { return; }\n");
        for (String invalid : new String[] { "", "xyz", "0".repeat(63), "G".repeat(64) }) {
            assertThrows(IllegalArgumentException.class, () ->
                    LinkedProgramRunner.runWithExitStatusVerified(
                            entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                            invalid, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        }
        assertThrows(IllegalArgumentException.class, () ->
                LinkedProgramRunner.runWithExitStatusVerified(
                        entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        null, new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }
}
