package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class CryptoSha256BuiltinTest {
    @TempDir Path root;

    @Test
    void guestComputesCanonicalUtf8Sha256() throws Exception {
        Path source = root.resolve("sha256.ores");
        Files.writeString(
                source,
                """
                pub routine main(): void {
                  stdio.stdout.write(crypto.sha256("abc"));
                  return;
                }
                """,
                StandardCharsets.UTF_8);

        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.STDOUT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        LinkedProgramRunner.run(
                source,
                policy,
                RuntimePermissions.denyAll(),
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                errors);

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad",
                output.toString(StandardCharsets.UTF_8));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }
}
