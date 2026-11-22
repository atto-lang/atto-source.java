package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        assertEquals(2, build.units().size(), "linked source should have two AttoLang/legacy units");
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
    void implicitAttoImportPrefersModernOverLegacyShadow(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        Files.copy(FIXTURES.resolve("math.atto"), work.resolve("math.atto"));
        Files.writeString(work.resolve("math.ores"),
                "define module Math as pub fnc add(int n): int { return 17; } end\n");
        assertGuestReturns42(work.resolve("main.atto"));
    }

    @Test
    void legacyOnlyImportRemainsAdmittedForMigration(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        Files.copy(FIXTURES.resolve("math.atto"), work.resolve("math.ores"));
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

    /**
     * Git blob identities are shared with LLVM PR #1415. This is a source
     * identity check, not proof either backend compiled or ran the guest.
     */
    @Test
    void sharedAttoFixtureBlobsMatchPinnedLlvmSource() throws Exception {
        var pinned = Map.of(
                "main.atto", "f152f9b46014975f471b827c82e8fe28107f9a0e",
                "math.atto", "01aa7b955eb3e69d9714c4a6893b6f900e493097",
                "explicit.atto", "f9502fb537f0a6b1bf2fd000f45ca2163bd9d30f");
        for (var fixture : pinned.entrySet()) {
            byte[] bytes = Files.readAllBytes(FIXTURES.resolve(fixture.getKey()));
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(bytes);
            assertEquals(fixture.getValue(), HexFormat.of().formatHex(digest.digest()),
                    "Java/LLVM source fixture drift: " + fixture.getKey());
        }
    }

    @Test
    void missingImportedAttoFailsBeforeExecution(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        assertThrows(Exception.class,
                () -> LinkedProgramRunner.validate(work.resolve("main.atto"), Map.of()),
                "missing relative AttoLang import must not be silently ignored");
    }

    @Test
    void invalidImportedAttoFailsBeforeExecution(@TempDir Path work) throws Exception {
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        Files.writeString(work.resolve("math.atto"),
                "define module Math as pub fnc add(int n): int { return missing_name; } end\n");
        assertThrows(Exception.class,
                () -> LinkedProgramRunner.validate(work.resolve("main.atto"), Map.of()),
                "invalid imported AttoLang code must not produce a runnable guest");
    }

    @Test
    void danglingCanonicalAttoCannotFallBackToLegacy(@TempDir Path work) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "symbolic-link behavior is separately tested on the Windows host");
        Files.copy(FIXTURES.resolve("main.atto"), work.resolve("main.atto"));
        Files.createSymbolicLink(work.resolve("math.atto"), work.resolve("missing-canonical.atto"));
        Files.writeString(work.resolve("math.ores"),
                "define module Math as pub fnc add(int n): int { return 17; } end\n");
        assertThrows(Exception.class,
                () -> LinkedProgramRunner.validate(work.resolve("main.atto"), Map.of()),
                "dangling .atto symlink cannot silently authorize the legacy shadow");
    }
}
