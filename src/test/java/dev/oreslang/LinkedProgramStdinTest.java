package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Regression: the CLI must route its OS stdin into the linked Graal context. */
final class LinkedProgramStdinTest {
    @TempDir Path workspace;

    @Test
    @Timeout(30)
    void explicitlyInjectedInputIsConsumedWithoutTransferringDescriptorOwnership()
            throws Exception {
        Path source = workspace.resolve("main.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  for const line of process.stdin {
                    writech process.stdout, "[" + line + "]\\n";
                  }
                  return;
                }
                """);
        final class HostInput extends ByteArrayInputStream {
            boolean wasClosed;
            HostInput(String bytes) {
                super(bytes.getBytes(StandardCharsets.UTF_8));
            }
            @Override public void close() {
                wasClosed = true;
            }
        }
        HostInput suppliedStdin = new HostInput("one\n\ntwo");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        IsolatePolicy policy = IsolatePolicy.developer();
        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                source,
                policy,
                RuntimePermissions.fromCapabilities(policy.capabilities()),
                PermissionCheckMode.COMPILE,
                ExecutionProfile.parse("jit", "linux"),
                Set.of(),
                Map.of(),
                suppliedStdin,
                out,
                err);
        assertEquals(0, result.exitStatus());
        assertEquals("[one]\n[]\n[two]\n", out.toString(StandardCharsets.UTF_8));
        assertFalse(suppliedStdin.wasClosed,
                "Oreslang Context.close must never close embedding caller's stdin");
    }
}
