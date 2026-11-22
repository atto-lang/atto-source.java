package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes actual linked Atto allocator policy guests. This is *not* proof
 * that compiler-owned physical allocation, destructor glue or AOT is live.
 * Never print a per-case receipt before the guest passed the exit oracle.
 */
final class AttoAllocParityGuestTest {
    private static final Path MANIFEST =
            Path.of("tests", "fixtures", "alloc-java-guest", "alloc-parity-v1.tsv");
    private static final List<String> CONTRACT_CASES = List.of(
            "layout.negative_size", "layout.invalid_alignment", "layout.overflow",
            "ledger.reserve_commit_retire", "ledger.over_quota",
            "ledger.duplicate_retirement", "ledger.stale_generation");

    private static String gitBlobSha(byte[] bytes) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        hash.update(bytes);
        return HexFormat.of().formatHex(hash.digest());
    }

    @Test
    void executeSevenLinkedGuestCasesWithExactFixtureReceipts() throws Exception {
        assertTrue(Files.isRegularFile(MANIFEST), "missing checked guest manifest");
        List<String> rows = Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)
                .stream().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        assertEquals(CONTRACT_CASES.size(), rows.size(), "missing or extra guest cases");

        for (int i = 0; i < rows.size(); i++) {
            String[] fields = rows.get(i).split("\t", -1);
            assertEquals(4, fields.length, "invalid manifest row");
            String id = fields[0], phase = fields[1], outcome = fields[2];
            assertEquals(CONTRACT_CASES.get(i), id, "wrong semantic case ordering");
            assertTrue(phase.equals("guest") || phase.equals("runtime"), id);
            assertTrue(outcome.equals("PASS") || outcome.equals("REJECT"), id);
            Path path = Path.of(fields[3]).normalize();
            assertTrue(path.startsWith(Path.of("tests", "fixtures", "alloc-java-guest")),
                    "guest fixture escaped allowed directory");
            assertTrue(path.toString().endsWith(".atto"), "not an .atto fixture");
            assertTrue(Files.isRegularFile(path), "missing guest fixture " + path);
            byte[] source = Files.readAllBytes(path);

            // A successful parser/link step alone is not the guest oracle.
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(path, Map.of()), id);
            var result = LinkedProgramRunner.runWithExitStatus(
                    path, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(),
                    "real linked guest did not enforce allocation policy: " + id);
            System.out.println("ATTO_ALLOC_CASE " + id + " " + phase + " " +
                    outcome + " " + gitBlobSha(source));
        }
    }
}
