package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles/executes the EXACT .atto fixture from LLVM #1308.
 * A host checksum implementation or parsed-only test cannot satisfy this gate.
 * No result is claimed until these tests execute on an exact GitHub PR head.
 */
@Timeout(180)
final class DopScalarGuestParityTest {
    private static final String BLOB = "aedc778e3e8dd2940600f047540c159b0129830a";
    private static final Path SOURCE = Path.of(
            "contracts", "dop-scalar-guest-v1", "soa-int-update.atto");
    @TempDir Path temp;

    private static byte[] pinnedSource() throws Exception {
        Path part = Path.of("");
        for (Path name : SOURCE) {
            part = part.resolve(name);
            assertFalse(Files.isSymbolicLink(part), "symlinked source component: " + part);
        }
        assertTrue(Files.isRegularFile(SOURCE, LinkOption.NOFOLLOW_LINKS),
                "missing reviewed exact LLVM .atto guest fixture");
        byte[] data = Files.readAllBytes(SOURCE);
        assertTrue(data.length > 0 && data.length <= 2048, "bounded exact fixture");
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(data);
        assertEquals(BLOB, HexFormat.of().formatHex(sha.digest()),
                "source bytes differ from LLVM #1308 canonical Git blob");
        return data;
    }

    private int runGuest(Path source) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                stdout, stderr);
        assertEquals(1, result.build().units().size(), "one linked source unit");
        return result.exitStatus();
    }

    @Test
    void canonicalSoaGuestCompilerAdmissionDoesNotRequireExecuting() throws Exception {
        Path entry = temp.resolve("compile-only-soa-int-update.atto");
        Files.write(entry, pinnedSource());
        var build = LinkedProgramRunner.validate(entry, Map.of());
        assertNotNull(build, "real linked-source compiler must accept canonical guest");
        assertEquals(1, build.units().size(), "one exactly pinned .atto source unit");
    }

    @Test
    void identicalScalarSoaSourceCompilesAndReturnsZeroFromRealJitGuest() throws Exception {
        byte[] source = pinnedSource();
        Path executableSource = temp.resolve("soa-int-update.atto");
        Files.write(executableSource, source);
        assertEquals(0, runGuest(executableSource),
                "real .atto guest must verify 32768 rows, 10 sweeps and checksum 537509888");
    }

    @Test
    void incorrectGuestChecksumIsDetectedByActualCompiledExecution() throws Exception {
        String source = new String(pinnedSource(), StandardCharsets.UTF_8);
        String accepted = "checksum == 537509888";
        assertTrue(source.contains(accepted), "canonical checksum guard");
        assertEquals(source.indexOf(accepted), source.lastIndexOf(accepted),
                "checksum guard must occur exactly once");
        Path negative = temp.resolve("wrong-checksum.atto");
        Files.writeString(negative,
                source.replace(accepted, "checksum == 537509889"),
                StandardCharsets.UTF_8);
        assertEquals(1, runGuest(negative),
                "a changed guest checksum must exit nonzero; no host reference substitution");
    }
}
