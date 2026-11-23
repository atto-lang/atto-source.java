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
        for (String name : Set.of("path_lexical", "sync", "telemetry")) {
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
                () -> CoreLibrarySources.source("std/../path_lexical"));
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

        assertTrue(build.units().containsKey("@std/path_lexical.atto"));
        assertTrue(build.units().containsKey("@std/sync.atto"));
        assertTrue(build.units().containsKey("@std/telemetry.atto"));
        String expected = String.join("\n",
                "2", "-1", "-1", "-1", "-1", "true", "false", "true",
                "true", "false", "true", "false", "true", "false",
                "true", "false", "true", "false", "false", "false", "false", "true",
                "9", "1", "99", "-1",
                "9", "14", "12", "11", "-1", "4", "1", "-1", "true", "false", "false", "-1", "false", "true",
                "true", "false", "false", "false", "true", "false",
                "2", "-1", "false", "233", "4",
                "2", "9", "1", "99", "9", "1", "-1", "1", "1", "false", "false", "false", "12", "11", "true", "true");
        assertEquals(expected,
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> stderr.toString(StandardCharsets.UTF_8));
    }
    @Test
    void invalidPathInstancesFailInGuestBeforeReturning() throws Exception {
        // Do not accept an import/parser rejection as proof that the Path
        // constructor itself enforced its lexical safety contract.
        for (String malformed : new String[] {
                "../escape", "CON.txt", "a//b", "C:/private"
        }) {
            Path entry = Files.createTempFile("atto-path-invalid-", ".atto");
            try {
                Files.writeString(entry, """
                        import module path from "std/path_lexical";
                        pub routine main(): void {
                          stdio.println(17);
                          val invalid = path.relative_path("%s", true);
                          stdio.println(invalid.component_count());
                          stdio.println(99);
                          return;
                        }
                        """.formatted(malformed));
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                assertThrows(Exception.class, () -> LinkedProgramRunner.run(
                        entry, IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(), stdout, stderr));
                assertEquals("17",
                        stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                        "must enter real guest before constructor rejects " + malformed);
            } finally {
                Files.deleteIfExists(entry);
            }
        }
    }

    @Test
    void all134SharedPolicyVectorsExecuteAndMutationChangesGuestExitStatus() throws Exception {
        // A native linked Atto execution witness, NOT a source-only model:
        // the same guest must return 0 for all 134 policy conditions, while
        // an inverted real import call must return nonzero from main().
        Path fixture = Path.of("tests", "fixtures", "atto_std_pure_policy_guest_v1.atto");
        String originalSource = Files.readString(fixture, StandardCharsets.UTF_8);
        assertEquals(134, originalSource.split("then return ", -1).length - 1,
                "the pinned fixture must contain exactly 134 failure exits");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var positive = LinkedProgramRunner.runWithExitStatus(fixture,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), stdout, stderr);
        for (String name : Set.of("path_lexical", "sync", "telemetry")) {
            assertTrue(positive.build().units().containsKey("@std/" + name + ".atto"),
                    "expected compiler-owned " + name + " unit in linked guest");
        }
        assertEquals(0, positive.exitStatus(),
                () -> "shared policy parity failed; stderr=" +
                        stderr.toString(StandardCharsets.UTF_8));
        assertEquals("", stdout.toString(StandardCharsets.UTF_8).trim());
        assertEquals("", stderr.toString(StandardCharsets.UTF_8).trim());

        String correct = "if !path.is_separator(47, false) then return 1; fi";
        String inverted = "if path.is_separator(47, false) then return 1; fi";
        assertTrue(originalSource.contains(correct), "missing first policy witness");
        Path negative = Files.createTempFile("atto-std-134-executed-negative-", ".atto");
        try {
            Files.writeString(negative, originalSource.replace(correct, inverted),
                    StandardCharsets.UTF_8);
            var changed = LinkedProgramRunner.runWithExitStatus(negative,
                    IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertTrue(changed.build().units().containsKey("@std/path_lexical.atto"));
            assertEquals(1, changed.exitStatus(),
                    "mutated import must execute the opposite policy arm in the real guest");
        } finally {
            Files.deleteIfExists(negative);
        }
    }

    @Test
    void applicationFilesCannotShadowTrustedStdModules() throws Exception {
        Path root = Files.createTempDirectory("atto-stdlib-shadow-");
        try {
            Path std = Files.createDirectories(root.resolve("std"));
            Files.writeString(std.resolve("path_lexical.atto"), """
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
                    import module path from "std/path_lexical";
                    import module sync from "std/sync";
                    import module telemetry as Telemetry from "std/telemetry";
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
            assertTrue(build.units().containsKey("@std/path_lexical.atto"));
            assertTrue(build.units().containsKey("@std/sync.atto"));
            assertTrue(build.units().containsKey("@std/telemetry.atto"));
            assertEquals(String.join("\n", "2", "false", "false"),
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
