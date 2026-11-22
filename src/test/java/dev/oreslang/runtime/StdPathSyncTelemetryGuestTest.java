package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
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
                "9", "1", "99", "-1",
                "9", "14", "12", "11", "-1", "4", "1", "-1", "true", "false", "false", "-1", "false", "true",
                "true", "false", "false", "false", "true", "false",
                "2", "-1", "false", "233", "4");
        assertEquals(expected,
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> stderr.toString(StandardCharsets.UTF_8));
    }
    @Test
    void applicationFilesCannotShadowTrustedStdModules() throws Exception {
        Path root = Files.createTempDirectory("atto-stdlib-shadow-");
        try {
            Path std = Files.createDirectories(root.resolve("std"));
            Files.writeString(std.resolve("path.atto"), """
                    define module path as
                      pub fnc normalized_depth(String name, bool windows): int {
                        return 999;
                      }
                    end
                    """);
            Files.writeString(std.resolve("sync.atto"), """
                    define module sync as
                      pub fnc mutex_acquire_admissible(bool locked, bool closed,
                          int waiting, int maximum): bool { return true; }
                    end
                    """);
            Files.writeString(std.resolve("telemetry.atto"), """
                    define module Telemetry as
                      pub fnc sampled_flag(int flags): bool { return true; }
                    end
                    """);
            Path entry = root.resolve("main.atto");
            Files.writeString(entry, """
                    import module path from "std/path";
                    import module sync from "std/sync";
                    import module Telemetry from "std/telemetry";
                    pub routine main(): void {
                      stdio.println(path.normalized_depth("a/b", false));
                      stdio.println(sync.mutex_acquire_admissible(true, false, 0, 8));
                      stdio.println(Telemetry.sampled_flag(2));
                      return;
                    }
                    """);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    stdout, stderr);
            assertTrue(build.units().containsKey("@std/path.atto"));
            assertTrue(build.units().containsKey("@std/sync.atto"));
            assertTrue(build.units().containsKey("@std/telemetry.atto"));
            assertEquals("2\\nfalse\\nfalse".replace("\\n", "\n"),
                    stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> stderr.toString(StandardCharsets.UTF_8));
        } finally {
            if (Files.exists(root)) {
                try (var entries = Files.walk(root)) {
                    for (Path file : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

}
