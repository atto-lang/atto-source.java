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
}
