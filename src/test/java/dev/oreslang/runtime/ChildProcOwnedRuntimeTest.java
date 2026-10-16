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

    private static OresContext context() {
        return new OresContext(ActorRuntime.currentActorRuntime());
    }

    @Test
    void capturesStdoutAndExitCodeWithoutGuestCarrierBlocking() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), workers, 2, 1024)) {
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
             ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), workers, 2, 8192)) {
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
             ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), workers, 2, 8)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "01234567890123456789"))
                    .get(10, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> child.waitAsync().get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void childAndRegistryAreOwnedByTheirOriginalActor() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2);
             ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), workers, 2, 1024)) {
            var child = manager.spawnAsync(spec(executable("/usr/bin/printf"), "hello"))
                    .get(10, TimeUnit.SECONDS);
            var exit = child.waitAsync().get(10, TimeUnit.SECONDS);
            assertEquals(0, exit.code());
            ActorRuntime.active = false; // boundary exercised via test-only actor stubs
            try {
                assertThrows(SecurityException.class, () -> manager.spawnAsync(spec(executable("/usr/bin/printf"), "bad")));
                assertThrows(SecurityException.class, child::kill);
            } finally {
                ActorRuntime.active = true;
            }
        }
    }

    @Test
    void registryCloseRejectsQueuedLaunchBeforeItStarts() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        Executor queuedExecutor = queued::addLast;
        ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), queuedExecutor, 1, 1024);
        var future = manager.spawnAsync(spec(executable("/usr/bin/printf"), "not-launched"));
        assertFalse(future.isDone());
        manager.close();
        assertTrue(future.isCompletedExceptionally());
        queued.removeFirst().run();
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    void directExecutorCannotStartProcessOnActorCarrier() throws Exception {
        try (ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(
                context(), Runnable::run, 1, 1024)) {
            var future = manager.spawnAsync(spec(executable("/usr/bin/printf"), "never-runs"));
            assertThrows(CompletionException.class, future::join);
        }
    }

    @Test
    void capacityIsEnforcedAcrossPendingLaunches() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        try (ChildProcOwnedRuntime manager =
                     ChildProcOwnedRuntime.forCurrentActor(context(), queued::addLast, 1, 1024)) {
            var first = manager.spawnAsync(spec(executable("/usr/bin/printf"), "queued"));
            assertFalse(first.isDone());
            assertThrows(IllegalStateException.class,
                    () -> manager.spawnAsync(spec(executable("/usr/bin/printf"), "rejected")));
        }
    }

    @Test
    void registryCloseTerminatesRunningChild() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ChildProcOwnedRuntime manager = ChildProcOwnedRuntime.forCurrentActor(context(), workers, 2, 1024);
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
}
