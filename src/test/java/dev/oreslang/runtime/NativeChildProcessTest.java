package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class NativeChildProcessTest {
    @TempDir Path root;

    @Test
    void scopedChildProcessHandleStartsObservesAndStopsWithoutShell() throws Exception {
        Path executable = sleepExecutable();
        Path source = root.resolve("child.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  val handle = process.spawn("%s", "30");
                  stdio.println(process.pid(rt copy handle) > 0);
                  stdio.println(process.running(rt copy handle));
                  stdio.println(process.stop(rt copy handle));
                  stdio.println(process.running(handle));
                  return;
                }
                """.formatted(q(executable)), StandardCharsets.UTF_8);

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.CHILD_PROCESS);
        RuntimePermissions permissions = new RuntimePermissions.Builder()
                .allow(RuntimePermissions.Permission.RUN, executable.toString())
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("true\ntrue\ntrue\nfalse\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void childProcessRequiresScopedRunPermission() throws Exception {
        Path executable = sleepExecutable();
        Path source = root.resolve("denied-child.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  process.spawn("%s", "30");
                  return;
                }
                """.formatted(q(executable)), StandardCharsets.UTF_8);

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.CHILD_PROCESS);
        Exception failure = assertThrows(
                Exception.class,
                () -> LinkedProgramRunner.run(
                        source,
                        policy,
                        RuntimePermissions.denyAll(),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(String.valueOf(failure).contains("process.spawn"), String.valueOf(failure));
    }

    @Test
    void contextCloseKillsUnstoppedChild() throws Exception {
        Path executable = sleepExecutable();
        Path source = root.resolve("cleanup-child.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  val handle = process.spawn("%s", "30");
                  stdio.println(process.pid(handle));
                  return;
                }
                """.formatted(q(executable)), StandardCharsets.UTF_8);

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.CHILD_PROCESS);
        RuntimePermissions permissions = new RuntimePermissions.Builder()
                .allow(RuntimePermissions.Permission.RUN, executable.toString())
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        long pid = Long.parseLong(out.toString(StandardCharsets.UTF_8).trim());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "Ores context close must not leak a child process");
    }

    private static Path sleepExecutable() throws Exception {
        Path candidate = Path.of("/usr/bin/sleep");
        Assumptions.assumeTrue(Files.isRegularFile(candidate) && Files.isExecutable(candidate),
                "/usr/bin/sleep is required for the native child-process contract test");
        return candidate.toRealPath();
    }

    private static String q(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
