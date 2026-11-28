package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actual Java guest-execution probe for the LRU harness's dynamically generated
 * nonzero-exit challenge. Independent source, no cross-organization mirror.
 * Does NOT establish native AOT or LRU guest parity.
 */
final class AttoExitChallengeAdmissionTest {
    @TempDir Path temp;

    @Test
    void generatedAttoRoutinesOutsideCompilerTreeHonorNonzeroExitStatus() throws Exception {
        // Mirrors the production receipt layout: an isolated external directory
        // containing a single synthesized .atto main and no project manifest.
        Path receipts = Files.createDirectory(temp.resolve("guest-receipts"));
        for (int status : new int[] {32, 37, 91, 111}) {
            Path entry = receipts.resolve("runner-exit-" + status + ".atto");
            Files.writeString(entry,
                    "pub routine main() -> int { return " + status + "; }\n",
                    StandardCharsets.UTF_8);
            OresProjectConfig project = OresProjectConfig.discover(entry, Map.of());
            assertTrue(project.manifestPath().isEmpty());
            assertEquals(receipts.toAbsolutePath().normalize(), project.projectRoot());

            var checked = LinkedProgramRunner.validate(entry, Map.of());
            assertEquals(1, checked.units().size());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            IsolatePolicy policy = IsolatePolicy.developer();
            var executed = LinkedProgramRunner.runWithExitStatus(
                    entry, policy,
                    RuntimePermissions.fromCapabilities(policy.capabilities()),
                    PermissionCheckMode.COMPILE, ExecutionProfile.serverJit(),
                    Set.of(), Map.of(), stdout, stderr);
            assertEquals(1, executed.build().units().size());
            assertEquals(status, executed.exitStatus());
            assertEquals("", stdout.toString(StandardCharsets.UTF_8));
            assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void validAttoChallengeCannotSucceedWithTheWrongStatus() throws Exception {
        Path entry = temp.resolve("runner-exit-challenge.atto");
        Files.writeString(entry, "pub routine main() -> int { return 73; }\n");
        IsolatePolicy policy = IsolatePolicy.developer();
        var run = LinkedProgramRunner.runWithExitStatus(
                entry, policy, RuntimePermissions.fromCapabilities(policy.capabilities()),
                PermissionCheckMode.COMPILE, ExecutionProfile.serverJit(),
                Set.of(), Map.of(), new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(73, run.exitStatus());
        assertFalse(run.exitStatus() == 0);
        assertFalse(run.exitStatus() == 72);
    }
}
