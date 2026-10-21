package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedProgramRunnerEnvironmentTest {
    @TempDir Path root;

    @Test
    void explicitRunnerEnvironmentIsVisibleToGuestWithoutHostMutation()
            throws Exception {
        String key = "ORES_CONTEXT_ENV_"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String value = "context-only-value";
        assertNull(System.getenv(key),
                "generated test key must not exist in ambient host environment");

        Path source = root.resolve("environment.ores");
        Files.writeString(
                source,
                """
                pub routine main(): void {
                  if env.has("%s"); then
                    stdio.stdout.write(env.get("%s").unwrap());
                  fi
                  return;
                }
                """.formatted(key, key),
                StandardCharsets.UTF_8);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, key);
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT,
                IsolatePolicy.Capability.STDOUT);
        Map<String, String> environment = new HashMap<>();
        environment.put(key, value);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                environment,
                output,
                errors);

        assertEquals(value, output.toString(StandardCharsets.UTF_8));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertNull(System.getenv(key),
                "runner injection must not mutate process environment");
    }
}
