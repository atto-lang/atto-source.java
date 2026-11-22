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
        try {
            // Surefire may set java.class.path to only a bootloader jar.
            // Locate the actual compiled test class directory instead.
            String testClasses = Path.of(ChildProcessProbe.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            return List.of("-cp", testClasses,
                    ChildProcessProbe.class.getName(), operation);
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException("cannot locate test child class", failure);
        }
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
            var error = assertThrows(ExecutionException.class,
                    () -> child.collectOutput().get(8, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof NativeChildProcesses.OutputLimitException);
            // Exceeding the quota is grounds for terminating the child; it
            // must not keep running to feed an unbounded output stream.
            child.waitForExit().get(8, TimeUnit.SECONDS);
            assertFalse(child.alive());
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
    @Test void unboundedProducerIsKilledOnFirstOutputOverflow() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(javaExe(), probe("infinite"), cwd(),
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class,
                    () -> child.collectOutput().get(8, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof NativeChildProcesses.OutputLimitException);
            child.waitForExit().get(8, TimeUnit.SECONDS);
            assertFalse(child.alive(), "unbounded-output child was not terminated");
        }
    }
    @Test void emptyArgumentIsAdmissibleAndNullArgumentIsNot() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            java.util.ArrayList<String> safe = new java.util.ArrayList<>(probe("exit42"));
            safe.add("");
            var child = manager.spawn(javaExe(), safe, cwd(),
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            assertEquals(42, child.waitForExit().get(8, TimeUnit.SECONDS).code());

            java.util.ArrayList<String> invalid = new java.util.ArrayList<>(safe);
            invalid.add(null);
            assertThrows(IllegalArgumentException.class,
                    () -> manager.spawn(javaExe(), invalid, cwd(),
                            (exe, dir) -> { }));
        }
    }
    @Test void invalidPathAndHugeArgumentDoNotReserveChildren() {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            assertThrows(IllegalArgumentException.class, () ->
                    manager.spawn("relative-executable", List.of(), cwd(),
                            (exe, dir) -> { }));
            assertThrows(IllegalArgumentException.class, () ->
                    manager.spawn(javaExe(), List.of("x".repeat(4_194_305)), cwd(),
                            (exe, dir) -> { }));
            assertEquals(0, manager.reservedCount());
        }
    }
    @Test void repeatTerminateReapsOnceAndDoesNotAccumulateWorkers() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(javaExe(), probe("sleep"), cwd(),
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            java.util.ArrayList<java.util.concurrent.CompletableFuture<
                    NativeChildProcesses.ExitStatus>> completions = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) completions.add(child.terminate());
            for (var pending : completions) pending.get(8, TimeUnit.SECONDS);
            assertFalse(child.alive());
        }
    }

    @Test void canonicalPermissionIsRecheckedBeforeAnyLaunch() throws Exception {
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            java.util.concurrent.atomic.AtomicInteger checks =
                    new java.util.concurrent.atomic.AtomicInteger();
            var pending = manager.spawn(javaExe(), probe("exit42"), cwd(),
                    (exe, dir) -> {
                        if (checks.incrementAndGet() == 2)
                            throw new SecurityException("canonical executable denied");
                    });
            var rejected = assertThrows(ExecutionException.class,
                    () -> pending.get(8, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof SecurityException);
            assertEquals(2, checks.get());
            assertEquals(0, manager.reservedCount());
        }
    }

    @Test void cancellationAndTimeoutKeepDistinctPublicErrorKinds() {
        assertEquals("cancelled", NativeChildProcesses.ProcessError.from(
                new java.util.concurrent.CancellationException()).kind());
        assertEquals("timeout", NativeChildProcesses.ProcessError.from(
                new java.util.concurrent.TimeoutException()).kind());
        assertEquals("permission_denied", NativeChildProcesses.ProcessError.from(
                new SecurityException("secret permission details")).kind());
    }

    @Test void contextShutdownKillsOwnedChildAndDrainsReservation() throws Exception {
        NativeChildProcesses manager = new NativeChildProcesses();
        var child = manager.spawn(javaExe(), probe("sleep"), cwd(),
                (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
        manager.close();
        assertFalse(child.alive());
        assertEquals(0, manager.reservedCount());
        manager.close(); // idempotent
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
                case "infinite" -> {
                    byte[] bytes = new byte[8192];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    while (true) System.out.write(bytes);
                }
                default -> throw new IllegalArgumentException("unknown probe");
            }
        }
    }
}
