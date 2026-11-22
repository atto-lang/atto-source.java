package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Graal/Truffle compile + linked execution of reviewed Orestra source.
 * The byte-for-byte fixture mirror and source blob IDs live in
 * src/test/resources/orestra-mirror/MIRROR_PROVENANCE.txt.
 */
final class OrestraNativeSourceIntegrationTest {
    private static final Path MIRROR =
            Path.of("src/test/resources/orestra-mirror").toAbsolutePath().normalize();
    private static final Path PROVENANCE = MIRROR.resolve("MIRROR_PROVENANCE.txt");

    private record Case(String filename, String sentinel) { }

    private static final List<Case> CASES = List.of(
            new Case("orestra/examples/placement-smoke.ores", "orestra-placement-ok"),
            new Case("orestra/examples/edge-guards.ores", "orestra-edge-guards-ok"),
            new Case("orestra/examples/workload-state-smoke.ores", "orestra-workload-state-ok"),
            new Case("sdk/examples/sdk-smoke.ores", "orestra-sdk-ok"));

    private static String gitBlobSha(Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void mirrorProvenanceBindsEveryReviewedFixture() throws Exception {
        assertTrue(Files.isRegularFile(PROVENANCE, LinkOption.NOFOLLOW_LINKS),
                "missing mirror provenance");
        Set<String> declared = new HashSet<>();

        for (String raw : Files.readAllLines(PROVENANCE, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Reviewed Orestra source.")) {
                continue;
            }
            String[] fields = line.split("\\s+");
            assertEquals(3, fields.length, "malformed provenance line: " + line);
            assertTrue(fields[1].matches("[0-9a-f]{40}"),
                    "malformed source blob SHA: " + line);

            Path relative = Path.of(fields[2]);
            assertFalse(relative.isAbsolute(), "absolute mirror path: " + fields[2]);
            Path fixture = MIRROR.resolve(relative).normalize();
            assertTrue(fixture.startsWith(MIRROR), "mirror path escapes root: " + fields[2]);
            assertTrue(Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS),
                    "missing/non-regular mirror fixture: " + fields[2]);
            assertFalse(Files.isSymbolicLink(fixture), "symlink mirror fixture: " + fields[2]);
            assertTrue(declared.add(relative.toString().replace('\\', '/')),
                    "duplicate mirror provenance path: " + fields[2]);
            assertEquals(fields[1], gitBlobSha(fixture),
                    "mirror bytes drifted from reviewed source blob: " + fields[2]);
        }

        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(MIRROR)) {
            paths.forEach(p -> {
                if (p.equals(PROVENANCE)) {
                    return;
                }
                assertFalse(Files.isSymbolicLink(p), "symlink inside mirror: " + p);
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    actual.add(MIRROR.relativize(p).toString().replace('\\', '/'));
                }
            });
        }
        assertEquals(declared, actual,
                "every mirrored fixture must be declared exactly once in provenance");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void linkedOreslangModulesCompileAndExecuteOnGraal() throws Exception {
        for (Case fixture : CASES) {
            Path entry = MIRROR.resolve(fixture.filename()).normalize();
            assertTrue(entry.startsWith(MIRROR) &&
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS),
                    fixture.filename());
            assertFalse(Files.isSymbolicLink(entry), fixture.filename());
            assertNotNull(LinkedProgramRunner.validate(entry), fixture.filename());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var result = LinkedProgramRunner.runWithExitStatus(
                    entry, IsolatePolicy.developer(),
                    ExecutionProfile.parse("jit", "server"), stdout, stderr);
            String out = stdout.toString(StandardCharsets.UTF_8);
            assertEquals(0, result.exitStatus(),
                    fixture.filename() + "\n" + out + "\n" + stderr);
            assertEquals(fixture.sentinel(), out.trim(),
                    fixture.filename() + "\n" + stderr.toString(StandardCharsets.UTF_8));
        }
    }
}
