package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoEnvStdlibTest {
    @TempDir Path temp;

    @Test
    void envModuleUsesClosedCanonicalAttoRegistry() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("env.atto"));
        assertEquals("@std/env.atto", CoreLibrarySources.unitId("std/env"));
        assertEquals(CoreLibrarySources.unitId("std/env"),
                CoreLibrarySources.unitId("ores.std/env.atto"));
        assertEquals("env.atto", CoreLibrarySources.fileName("std/env"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/env.ores"));
        String source = CoreLibrarySources.source("std/env");
        var parsed = MixedSourceUnit.parse(CoreLibrarySources.unitId("std/env"),
                CoreLibrarySources.fileName("std/env"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
    }

    @Test
    void linkedGuestExecutesStdEnvFallbackAndMissingSemantics() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/env.atto"),
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.ENVIRONMENT),
                ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/env.atto"),
                "source library must compile and link as a guest module");
        assertEquals(String.join("\n", "false", "true", "fallback", "true"),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scopedDenyPrecedesHostEnvironmentLookup() throws Exception {
        Path entry = temp.resolve("deny.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  std_env.get("PATH");
                  return;
                }
                """);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.ENVIRONMENT),
                        RuntimePermissions.denyAll(),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(deepMessage(error).contains("permission denied"), deepMessage(error));
    }

    private static String deepMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) message.append(current.getMessage()).append(" ");
        }
        return message.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
