package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic tests for closing an actor registry while OS launch is in flight. */
final class ChildProcOwnedRaceTest {
    private static ChildProcLaunchAdmission.Spec sleepSpec() {
        Path executable = Path.of("/usr/bin/sleep");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(executable));
        return new ChildProcLaunchAdmission.Spec(executable.toString(), List.of("30"),
                Path.of("").toAbsolutePath().toString(), Map.of());
    }

    private static ChildProcOwnedRuntime.Admission allow() {
        return new ChildProcOwnedRuntime.Admission() {
            @Override public void requireOwner() { }
            @Override public ChildProcLaunchAdmission.Plan admit(
                    ChildProcLaunchAdmission.Spec spec) throws IOException {
                return ChildProcLaunchAdmission.admit(spec, (permission, resource) -> { });
            }
        };
    }

    @Test
    void closingBetweenOsLaunchAndRegistrationKillsChildAndRejectsFuture() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Process> childProcess = new AtomicReference<>();
        ChildProcOwnedRuntime.ProcessStarter stalled = plan -> {
            Process process = ChildProcLaunchAdmission.newProcessBuilder(plan).start();
            childProcess.set(process);
            created.countDown();
            try {
                if (!resume.await(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("timed out at launch barrier");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return process;
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             ChildProcOwnedRuntime manager = new ChildProcOwnedRuntime(
                     allow(), executor, 1, 1024, stalled)) {
            CompletableFuture<ChildProcOwnedRuntime.Child> pending =
                    manager.spawnAsync(sleepSpec());
            assertTrue(created.await(5, TimeUnit.SECONDS), "real process must be started");
            Process process = childProcess.get();
            assertTrue(process.isAlive());
            long started = System.nanoTime();
            manager.close();
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
                    "actor close must not block on a paused OS launch");
            assertTrue(pending.isCompletedExceptionally());
            resume.countDown();
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            process.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(process.isAlive(), "orphaned subprocess after close-vs-launch");
        } finally {
            resume.countDown();
            Process process = childProcess.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void repeatedConcurrentCloseAndLaunchCannotLeaveLiveSubprocesses() throws Exception {
        for (int attempt = 0; attempt < 15; attempt++) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicReference<Process> launched = new AtomicReference<>();
            ChildProcOwnedRuntime.ProcessStarter starter = plan -> {
                Process process = ChildProcLaunchAdmission.newProcessBuilder(plan).start();
                launched.set(process);
                entered.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("barrier timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                return process;
            };
            try (ExecutorService executor = Executors.newSingleThreadExecutor();
                 ChildProcOwnedRuntime manager = new ChildProcOwnedRuntime(
                         allow(), executor, 1, 1024, starter)) {
                var pending = manager.spawnAsync(sleepSpec());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                manager.close();
                resume.countDown();
                assertTrue(pending.isCompletedExceptionally());
                launched.get().onExit().get(5, TimeUnit.SECONDS);
                assertFalse(launched.get().isAlive());
            } finally {
                resume.countDown();
                Process process = launched.get();
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }
    }
    @Test
    void rejectedHostExecutorReleasesPendingCapacityForRetry() throws Exception {
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.Executor executor = command -> {
                if (submissions.getAndIncrement() == 0) {
                    throw new java.util.concurrent.RejectedExecutionException("host worker unavailable");
                }
                workers.execute(command);
            };
            try (ChildProcOwnedRuntime manager =
                         new ChildProcOwnedRuntime(allow(), executor, 1, 1024)) {
                var first = manager.spawnAsync(printfSpec("unused"));
                assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
                var second = manager.spawnAsync(printfSpec("retried"))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(0, second.waitAsync().get(5, TimeUnit.SECONDS).code());
            }
        }
    }

    @Test
    void processStartFailureDoesNotStrandOwnerSlot() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ChildProcOwnedRuntime.ProcessStarter starter = plan -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("host process start failed");
            }
            return ChildProcLaunchAdmission.newProcessBuilder(plan).start();
        };
        try (ExecutorService workers = Executors.newSingleThreadExecutor();
             ChildProcOwnedRuntime manager =
                     new ChildProcOwnedRuntime(allow(), workers, 1, 1024, starter)) {
            var first = manager.spawnAsync(printfSpec("unused"));
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            var second = manager.spawnAsync(printfSpec("working"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("working", new String(second.waitAsync()
                    .get(5, TimeUnit.SECONDS).stdout(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void shutdownCanFinishWhileCompletionCallbackWaitsForAnotherThread() throws Exception {
        java.util.ArrayDeque<Runnable> pendingWork = new java.util.ArrayDeque<>();
        try (ExecutorService workers = Executors.newSingleThreadExecutor();
             ChildProcOwnedRuntime manager =
                     new ChildProcOwnedRuntime(allow(), pendingWork::addLast, 1, 1024)) {
            var future = manager.spawnAsync(printfSpec("unused"));
            java.util.concurrent.atomic.AtomicBoolean callbackRan = new java.util.concurrent.atomic.AtomicBoolean();
            future.whenComplete((value, failed) -> {
                // Runs synchronously during close(). Another thread must be
                // able to enter the registry monitor without deadlocking.
                try {
                    workers.submit(() -> {
                        try { manager.spawnAsync(printfSpec("denied")); }
                        catch (IllegalStateException expected) { return; }
                        catch (IOException error) { throw new AssertionError(error); }
                        throw new AssertionError("closed registry accepted launch");
                    }).get(3, TimeUnit.SECONDS);
                    callbackRan.set(true);
                } catch (Exception error) {
                    throw new AssertionError("completion callback ran under registry lock", error);
                }
            });
            manager.close();
            assertTrue(callbackRan.get());
            assertTrue(future.isCompletedExceptionally());
            pendingWork.removeFirst().run();
        }
    }

    private static ChildProcLaunchAdmission.Spec printfSpec(String text) {
        Path executable = Path.of("/usr/bin/printf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(executable));
        return new ChildProcLaunchAdmission.Spec(executable.toString(), List.of(text),
                Path.of("").toAbsolutePath().toString(), Map.of());
    }

}
