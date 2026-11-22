package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class StdPathSyncTelemetryGuestTest {
    @Test
    void exactCompilerOwnedRegistryContainsNativeAttoSourceOnly() throws Exception {
        for (String name : Set.of("path", "sync", "telemetry")) {
            String source = CoreLibrarySources.source("std/" + name);
            assertTrue(source.contains("define module "), name);
            assertFalse(source.contains("java:"), name);
            assertFalse(source.contains("do-java"), name);
            assertEquals(CoreLibrarySources.unitId("std/" + name),
                    CoreLibrarySources.unitId("std/" + name + ".atto"));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + name + ".ores"),
                    "new libraries must not acquire unapproved legacy aliases");
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(name + ".atto"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/../path"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/../sync"));
    }

    @Test
    void importedNativeAttoPoliciesExecuteInLinkedGuest() throws Exception {
        Path fixture = Path.of("tests", "fixtures", "path_sync_telemetry_v1.atto");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        var build = LinkedProgramRunner.run(
                fixture, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                stdout, stderr);

        assertTrue(build.units().containsKey("@std/path.atto"));
        assertTrue(build.units().containsKey("@std/sync.atto"));
        assertTrue(build.units().containsKey("@std/telemetry.atto"));
        String expected = String.join("\n",
                "2", "-1", "-1", "-1", "-1", "true", "false", "true",
                "true", "false", "true", "false", "true", "false",
                "true", "false", "true", "false", "false", "false", "false", "true",
                "9", "1", "99", "-1");
        assertEquals(expected,
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> stderr.toString(StandardCharsets.UTF_8));
    }
}
