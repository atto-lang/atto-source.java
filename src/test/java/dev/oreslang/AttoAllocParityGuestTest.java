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
    private record Case(String id, String phase, String outcome, String fixture) { }
    private static final List<Case> CONTRACT_CASES = List.of(
            new Case("layout.negative_size", "runtime", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/layout-negative-size.atto"),
            new Case("layout.invalid_alignment", "runtime", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/layout-invalid-alignment.atto"),
            new Case("layout.overflow", "runtime", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/layout-overflow.atto"),
            new Case("ledger.reserve_commit_retire", "guest", "PASS",
                    "tests/fixtures/alloc-java-guest/allocator-transactions-positive.atto"),
            new Case("ledger.over_quota", "guest", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/ledger-over-quota.atto"),
            new Case("ledger.duplicate_retirement", "guest", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/ledger-duplicate-retirement.atto"),
            new Case("ledger.stale_generation", "guest", "REJECT",
                    "tests/fixtures/alloc-java-guest/parity-v1/ledger-stale-generation.atto"));

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
            Case expected = CONTRACT_CASES.get(i);
            assertEquals(expected.id(), id, "wrong semantic case ordering");
            assertEquals(expected.phase(), phase, "wrong contract execution phase");
            assertEquals(expected.outcome(), outcome, "wrong semantic outcome");
            assertEquals(expected.fixture(), fields[3], "wrong exact fixture mapping");
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
