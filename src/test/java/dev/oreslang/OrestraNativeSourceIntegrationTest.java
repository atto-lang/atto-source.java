package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
    private record Case(String filename, String sentinel) { }
    private static final List<Case> CASES = List.of(
            new Case("orestra/examples/placement-smoke.ores", "orestra-placement-ok"),
            new Case("orestra/examples/edge-guards.ores", "orestra-edge-guards-ok"),
            new Case("orestra/examples/workload-state-smoke.ores", "orestra-workload-state-ok"),
            new Case("sdk/examples/sdk-smoke.ores", "orestra-sdk-ok"));

    @Test @Timeout(value=120, unit=TimeUnit.SECONDS)
    void linkedOreslangModulesCompileAndExecuteOnGraal() throws Exception {
        for (Case fixture : CASES) {
            Path entry = MIRROR.resolve(fixture.filename()).normalize();
            assertTrue(entry.startsWith(MIRROR) && entry.toFile().isFile(), fixture.filename());
            assertNotNull(LinkedProgramRunner.validate(entry), fixture.filename());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var result = LinkedProgramRunner.runWithExitStatus(
                    entry, IsolatePolicy.developer(),
                    ExecutionProfile.parse("jit", "server"), stdout, stderr);
            String out = stdout.toString(StandardCharsets.UTF_8);
            assertEquals(0, result.exitStatus(), fixture.filename() + "\n" + out + "\n" + stderr);
            assertEquals(fixture.sentinel(), out.trim(),
                    fixture.filename() + "\n" + stderr.toString(StandardCharsets.UTF_8));
        }
    }
}
