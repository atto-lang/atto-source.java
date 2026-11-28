package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Guest semantics live in .atto; this is only an exit-status/provenance bridge. */
@Timeout(120)
final class DopSharedSoaGuestTest {
    private static final Path FIXTURE = Path.of(
            "src/test/resources/dop-shared-v1/soa-int-update.atto");
    @TempDir Path temp;

    @Test
    void fixtureMatchesPinnedLlvmGitBlob() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURE);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        assertEquals("aedc778e3e8dd2940600f047540c159b0129830a",
                HexFormat.of().formatHex(digest.digest(bytes)));
    }

    private int execute(Path entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(entry,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        return result.exitStatus();
    }

    @Test
    void identicalLlvmGuestReturnsZero() throws Exception {
        assertEquals(0, execute(FIXTURE));
    }

    @Test
    void incorrectChecksumIsObservedAsGuestFailure() throws Exception {
        String original = Files.readString(FIXTURE);
        String expected = "return checksum == 537509888";
        assertTrue(original.contains(expected), "mutation must hit the actual oracle");
        String mutated = original.replace(expected, "return checksum == 537509889");
        assertNotEquals(original, mutated);
        Path entry = temp.resolve("incorrect-checksum.atto");
        Files.writeString(entry, mutated);
        assertEquals(1, execute(entry),
                "a compiled guest returning failure must not be counted as a pass");
    }
}
