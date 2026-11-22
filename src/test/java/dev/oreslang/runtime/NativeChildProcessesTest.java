package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real OS subprocess proof for the JVM provider, independent of guest compiler. */
final class NativeChildProcessesTest {
    private static String javaExe() {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + suffix)
                .toAbsolutePath().normalize().toString();
    }
    private static String cwd() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
    }
    private static List<String> probe(String operation) {
        return List.of("-cp", System.getProperty("java.class.path"),
                ChildProcessProbe.class.getName(), operation);
    }
    @Test void childExitAndConcurrentStdoutStderrPumps() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(javaExe(), probe("exit42"), cwd(), (exe, dir) -> { })
                    .get(8, TimeUnit.SECONDS);
            var output = child.collectOutput().get(8, TimeUnit.SECONDS);
            var status = child.waitForExit().get(8, TimeUnit.SECONDS);
            assertEquals(42, status.code());
            assertFalse(status.signaled());
            assertTrue(output.stdout().contains("stdout-ok"));
            assertTrue(output.stderr().contains("stderr-ok"));
        }
    }
    @Test void permissionDenialHappensBeforeProcessSpawn() {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            assertThrows(SecurityException.class,
                    () -> manager.spawn(javaExe(), probe("exit42"), cwd(),
                            (exe, dir) -> { throw new SecurityException("denied"); }));
            assertEquals(0, manager.reservedCount());
        }
    }
    @Test void impossibleCommandIsAnErrorNotAnExitCode() {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var pending = manager.spawn("/unlikely-atto-missing-binary-3299",
                    List.of(), cwd(), (exe, dir) -> { });
            assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
        }
    }
    @Test void boundedCaptureReportsErrorAndStillReaps() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(javaExe(), probe("oversize"), cwd(),
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            assertEquals(0, child.waitForExit().get(8, TimeUnit.SECONDS).code());
            var error = assertThrows(ExecutionException.class,
                    () -> child.collectOutput().get(8, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof NativeChildProcesses.OutputLimitException);
        }
    }
    @Test void forceKillMakesWaitComplete() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(javaExe(), probe("sleep"), cwd(),
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            child.kill().get(8, TimeUnit.SECONDS);
            assertFalse(child.alive());
        }
    }
    @Test void rejectsArgumentsWithEmbeddedNulWithoutLaunch() {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.spawn(javaExe(), List.of("a\0b"), cwd(),
                            (exe, dir) -> { }));
            assertEquals(0, manager.reservedCount());
        }
    }
    public static final class ChildProcessProbe {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "exit42" -> {
                    System.out.println("stdout-ok");
                    System.err.println("stderr-ok");
                    System.exit(42);
                }
                case "oversize" -> {
                    byte[] data = new byte[8192];
                    java.util.Arrays.fill(data, (byte) 'x');
                    for (int i = 0; i < 150; i++) System.out.write(data);
                    System.out.flush();
                }
                case "sleep" -> Thread.sleep(30_000);
                default -> throw new IllegalArgumentException("unknown probe");
            }
        }
    }
}
