package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Java/Graal counterpart to LLVM native_atto_first_source_import_aot_parity.
 * Tests real linked .atto guest execution; no synthetic or host emulation.
 * LLVM O0/O2 and Java JIT results must be recorded independently.
 */
final class AttoFirstLlvmImportParityTest {
    private static final Path FIXTURES =
            Path.of("tests", "parity", "atto-llvm-import");

    private static void assertGuestReturns42(Path entry) throws Exception {
        var build = LinkedProgramRunner.validate(entry, Map.of());
        // Real imported source, not a Java hard-coded replacement.
        assertEquals(2, build.units().size(), "linked source should have two .atto units");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                stdout,
                stderr);
        assertEquals(42, result.exitStatus(), "main() must return native parity sentinel");
        assertEquals("", stdout.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void relativeAttoImportExecutesGuest() throws Exception {
        assertGuestReturns42(FIXTURES.resolve("main.atto"));
    }

    @Test
    void explicitAttoImportDoesNotSelectLegacyShadow(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("explicit.atto"), work.resolve("main.atto"));
        Files.copy(FIXTURES.resolve("math.atto"), work.resolve("math.atto"));
        Files.writeString(work.resolve("math.ores"),
                "define module Math as pub fnc add(int n): int { return 17; } end\n");
        assertGuestReturns42(work.resolve("main.atto"));
    }

    @Test
    void canonicalNonregularSourceCannotFallBackToLegacy(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        Files.createDirectory(work.resolve("math.atto"));
        Files.writeString(work.resolve("math.ores"),
                "define module Math as pub fnc add(int n): int { return 17; } end\n");
        assertThrows(Exception.class,
                () -> LinkedProgramRunner.validate(work.resolve("main.atto"), Map.of()),
                "directory masquerading as canonical .atto must not select legacy source");
    }
}
