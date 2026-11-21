package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class NativeGuestArgumentsTest {
    @TempDir Path root;

    @Test
    void linkedRunnerExposesOnlyExplicitImmutableGuestArguments() throws Exception {
        Path source = root.resolve("argv.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  stdio.println(process.argv_size());
                  stdio.println(process.argv_at(0));
                  stdio.println(process.argv_at(1));
                  stdio.println(process.argv_at(2));
                  return;
                }
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.RunResult result =
                LinkedProgramRunner.runWithExitStatus(
                        source,
                        IsolatePolicy.developer(),
                        RuntimePermissions.fromCapabilities(
                                IsolatePolicy.developer().capabilities()),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        List.of("plan", "alpha beta", "--ores-capabilities=NOT_A_POLICY"),
                        out,
                        new ByteArrayOutputStream());

        assertEquals(0, result.exitStatus());
        assertEquals(
                "3\nplan\nalpha beta\n--ores-capabilities=NOT_A_POLICY\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void argvBoundsFailClosed() throws Exception {
        Path source = root.resolve("argv-bounds.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  stdio.println(process.argv_at(1));
                  return;
                }
                """, StandardCharsets.UTF_8);

        Exception failure = assertThrows(
                Exception.class,
                () -> LinkedProgramRunner.runWithExitStatus(
                        source,
                        IsolatePolicy.developer(),
                        RuntimePermissions.fromCapabilities(
                                IsolatePolicy.developer().capabilities()),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        List.of("only"),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(
                String.valueOf(failure.getMessage()).contains("argv_at index out of range"),
                String.valueOf(failure));
    }
}
