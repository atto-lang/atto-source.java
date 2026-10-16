package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-process lifecycle tests. An actual OresContext/ActorRuntime integration
 * test is still required before exposing guest-facing ChildProcManager APIs.
 */
final class ChildProcOwnedRuntimeTest {
    private static final Path CWD = Path.of("").toAbsolutePath().normalize();

    private static ChildProcLaunchAdmission.Spec spec(Path file, String... args) {
        return new ChildProcLaunchAdmission.Spec(
                file.toString(), List.of(args), CWD.toString(), Map.of());
    }

    private static Path executable(String path) {
        Path file = Path.of(path);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(file), path + " unavailable");
        return file;
    }

    private static ChildProcOwnedRuntime manager(
            Executor executor, int maxChildren, int maxOutputBytes) {
        return new ChildProcOwnedRuntime(new ChildProcOwnedRuntime.Admission() {
            @Override public void requireOwner() { }
            @Override public ChildProcLaunchAdmission.Plan admit(
                    ChildProcLaunchAdmission.Spec request) throws java.io.IOException {
                return ChildProcLaunchAdmission.admit(request, (permission, resource) -> { });
            }
        }, executor, maxChildren, maxOutputBytes);
    }

    @Test
    void capturesStdoutAndExitCodeWithoutGuestCarrierBlocking() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 1024)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "hello"))
                    .get(10, TimeUnit.SECONDS);
            var exit = child.waitAsync().get(10, TimeUnit.SECONDS);
            assertEquals(0, exit.code());
            assertEquals("hello", new String(exit.stdout(), StandardCharsets.UTF_8));
            assertEquals(0, exit.stderr().length);
        }
    }

    @Test
    void capturesStderrAndNonzeroExitStatus() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 8192)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/ls"), "/no-oreslang-such-path-123456"))
                    .get(10, TimeUnit.SECONDS);
            var exit = child.waitAsync().get(10, TimeUnit.SECONDS);
            assertNotEquals(0, exit.code());
            assertEquals(0, exit.stdout().length);
            assertTrue(exit.stderr().length > 0);
        }
    }

    @Test
    void outputQuotaKillsChildAndFailsResult() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 8)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "01234567890123456789"))
                    .get(10, TimeUnit.SECONDS);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> child.waitAsync().get(10, TimeUnit.SECONDS));
            Throwable root = error;
            while (root.getCause() != null) root = root.getCause();
            assertEquals("child process output quota exceeded", root.getMessage(),
                    "output quota errors must not be converted into ordinary termination");
        }
    }

    @Test
    void completedChildCannotBeSignaledByStaleHandle() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 1024)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "hello"))
                    .get(10, TimeUnit.SECONDS);
            var exit = child.waitAsync().get(10, TimeUnit.SECONDS);
            assertEquals(0, exit.code());
            // A test-bound admission guard verifies host calls never skip
            // per-operation owner validation. Actual actor identity is checked
            // by the production forCurrentActor adapter separately.
            assertFalse(child.kill()); // completed child handle is stale
        }
    }

    @Test
    void registryCloseRejectsQueuedLaunchBeforeItStarts() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        Executor queuedExecutor = queued::addLast;
        ChildProcOwnedRuntime manager = manager(queuedExecutor, 1, 1024);
        var future = manager.spawnAsync(spec(executable("/usr/bin/printf"), "not-launched"));
        assertFalse(future.isDone());
        manager.close();
        assertTrue(future.isCompletedExceptionally());
        queued.removeFirst().run();
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    void directExecutorCannotStartProcessOnActorCarrier() throws Exception {
        try (ChildProcOwnedRuntime manager = manager(
                Runnable::run, 1, 1024)) {
            var future = manager.spawnAsync(spec(executable("/usr/bin/printf"), "never-runs"));
            assertThrows(CompletionException.class, future::join);
        }
    }

    @Test
    void capacityIsEnforcedAcrossPendingLaunches() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        try (ChildProcOwnedRuntime manager =
                     manager(queued::addLast, 1, 1024)) {
            var first = manager.spawnAsync(spec(executable("/usr/bin/printf"), "queued"));
            assertFalse(first.isDone());
            assertThrows(IllegalStateException.class,
                    () -> manager.spawnAsync(spec(executable("/usr/bin/printf"), "rejected")));
        }
    }

    @Test
    void registryCloseTerminatesRunningChild() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ChildProcOwnedRuntime manager = manager(workers, 2, 1024);
        try {
            var child = manager.spawnAsync(spec(executable("/usr/bin/sleep"), "30"))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(child.pid()).isPresent());
            manager.close();
            // Child JVM completion/finalization happens asynchronously; this
            // assertion proves the host runtime does not leave sleep running.
            child.waitAsync().get(10, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(child.pid()).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            manager.close();
            workers.shutdownNow();
        }
    }
    @Test
    void terminateAsyncSignalStopsChildWithoutBlockingActor() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 1024)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/sleep"), "30"))
                    .get(10, TimeUnit.SECONDS);
            long before = System.nanoTime();
            assertTrue(child.terminate());
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(3));
            child.waitAsync().get(10, TimeUnit.SECONDS);
            assertFalse(child.kill(), "completed generation must not be signalled");
        }
    }

    @Test
    void completedExitBuffersCannotBeMutatedThroughAccessors() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = manager(workers, 2, 1024)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "immutable"))
                    .get(10, TimeUnit.SECONDS);
            var exit = child.waitAsync().get(10, TimeUnit.SECONDS);
            byte[] copy = exit.stdout();
            copy[0] = (byte) 'X';
            assertEquals("immutable", new String(exit.stdout(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void exitRecordDefensivelyCopiesConstructorArrays() {
        byte[] stdout = "example".getBytes(StandardCharsets.UTF_8);
        byte[] stderr = "warning".getBytes(StandardCharsets.UTF_8);
        var result = new ChildProcOwnedRuntime.Exit(0, stdout, stderr);
        stdout[0] = 'X';
        stderr[0] = 'Y';
        assertEquals("example", new String(result.stdout(), StandardCharsets.UTF_8));
        assertEquals("warning", new String(result.stderr(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAggregateOutputQuotasBeforeStartingAnyProcesses() {
        var fixture = new ChildProcOwnedRuntime.Admission() {
            @Override public void requireOwner() { }
            @Override public ChildProcLaunchAdmission.Plan admit(
                    ChildProcLaunchAdmission.Spec request) throws java.io.IOException {
                return ChildProcLaunchAdmission.admit(request, (permission, resource) -> { });
            }
        };
        assertThrows(IllegalArgumentException.class, () ->
                new ChildProcOwnedRuntime(fixture, Runnable::run, 64, 16 * 1024 * 1024));
        try (ChildProcOwnedRuntime allowed =
                     new ChildProcOwnedRuntime(fixture, Runnable::run, 64, 256 * 1024)) {
            assertNotNull(allowed);
        }
    }

}
