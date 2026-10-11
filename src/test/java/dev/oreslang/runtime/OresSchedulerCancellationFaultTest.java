package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cancellation fault injection and shutdown admission tests. */
@Timeout(20)
final class OresSchedulerCancellationFaultTest {

    private static void untilRegistered(OresFuture<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (future.pendingRuntimeWaiterCount() != 1 &&
                System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(1, future.pendingRuntimeWaiterCount(),
                "source continuation did not register its awaited Future");
    }

    @Test
    void failingSuspendedCleanupRetainsTheOriginalFailureAndRunsOnlyOnce() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> upstream = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            AtomicInteger steps = new AtomicInteger();
            AtomicInteger cleanupEntries = new AtomicInteger();
            IllegalStateException failure = new IllegalStateException("cleanup failed");

            OresFuture<Integer> result = scheduler.start(
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cleaning;
                        @Override public boolean cleanupInProgress() { return cleaning; }
                        @Override public void cancellationRequested(
                                OresScheduler.TaskCancelledException reason) { }
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resumed) {
                            switch (steps.getAndIncrement()) {
                                case 0 -> { return OresScheduler.await(upstream); }
                                case 1 -> {
                                    assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                            resumed.failure());
                                    assertSame(scheduler, OresScheduler.current());
                                    cleaning = true;
                                    cleanupEntries.incrementAndGet();
                                    return OresScheduler.await(cleanup);
                                }
                                case 2 -> {
                                    assertSame(failure, resumed.failure(),
                                            "cleanup error identity must not be replaced");
                                    assertSame(scheduler, OresScheduler.current());
                                    throw failure;
                                }
                                default -> throw new AssertionError("replayed cleanup continuation");
                            }
                        }
                    });

            untilRegistered(upstream);
            assertTrue(result.cancel(false));
            untilRegistered(cleanup);
            assertFalse(result.isDone());
            assertFalse(result.cancel(false), "duplicate cancellation was admitted");
            assertEquals(0, upstream.pendingRuntimeWaiterCount());
            assertTrue(cleanup.failFromRuntime(failure));
            ExecutionException observed = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertSame(failure, observed.getCause());
            assertEquals(1, cleanupEntries.get());
            assertEquals(3, steps.get());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void shutdownTimeoutReportsUnfinishedCleanupRatherThanSuccess() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> upstream = new OresFuture<>();
        OresFuture<Integer> neverCompletes = new OresFuture<>();
        AtomicInteger cleanupEntries = new AtomicInteger();
        OresFuture<Integer> task = scheduler.start(
                new OresScheduler.CancellationAwareTask<>() {
                    private volatile boolean cleaning;
                    @Override public boolean cleanupInProgress() { return cleaning; }
                    @Override public void cancellationRequested(
                            OresScheduler.TaskCancelledException reason) { }
                    @Override public OresScheduler.Step<Integer> resume(
                            OresScheduler.Resume resumed) {
                        if (resumed.initial()) return OresScheduler.await(upstream);
                        if (!cleaning) {
                            assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                    resumed.failure());
                            cleaning = true;
                            cleanupEntries.incrementAndGet();
                            return OresScheduler.await(neverCompletes);
                        }
                        assertEquals(7, resumed.value());
                        assertNull(resumed.failure());
                        cleaning = false;
                        return OresScheduler.done(42);
                    }
                });
        untilRegistered(upstream);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                scheduler::close);
        assertTrue(error.getMessage().contains("unfinished cancellation cleanup"),
                error.getMessage());
        assertEquals(1, cleanupEntries.get());
        assertFalse(task.isDone(), "timeout must not pretend guest cleanup has finished");
        assertEquals(0, upstream.pendingRuntimeWaiterCount());
        assertEquals(1, neverCompletes.pendingRuntimeWaiterCount(),
                "cleanup waiter and context ownership survive the first failed close");
        assertTrue(neverCompletes.completeFromRuntime(7));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> task.get(5, TimeUnit.SECONDS));
        assertEquals(0, neverCompletes.pendingRuntimeWaiterCount());
        assertDoesNotThrow(scheduler::close,
                "a second close may retire the carrier after actual cleanup");
        assertEquals(1, cleanupEntries.get());
    }

    @Test
    void repeatedConcurrentCloseAndCleanupSettlementCannotReplayCleanup() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 32; iteration++) {
                OresScheduler scheduler = new OresScheduler(1);
                try {
                    OresFuture<Integer> upstream = new OresFuture<>();
                    OresFuture<Integer> cleanup = new OresFuture<>();
                    AtomicInteger stages = new AtomicInteger();
                    AtomicInteger cleanupEntries = new AtomicInteger();
                    AtomicBoolean cleaning = new AtomicBoolean();
                    OresFuture<Integer> task = scheduler.start(
                            new OresScheduler.CancellationAwareTask<>() {
                                @Override public boolean cleanupInProgress() {
                                    return cleaning.get();
                                }
                                @Override public void cancellationRequested(
                                        OresScheduler.TaskCancelledException reason) { }
                                @Override public OresScheduler.Step<Integer> resume(
                                        OresScheduler.Resume resumed) {
                                    int stage = stages.getAndIncrement();
                                    if (stage == 0) return OresScheduler.await(upstream);
                                    if (stage == 1) {
                                        assertInstanceOf(
                                                OresScheduler.TaskCancelledException.class,
                                                resumed.failure());
                                        cleaning.set(true);
                                        cleanupEntries.incrementAndGet();
                                        return OresScheduler.await(cleanup);
                                    }
                                    assertEquals(2, stage,
                                            "cleanup continuation was delivered twice");
                                    assertNull(resumed.failure());
                                    assertSame(scheduler, OresScheduler.current());
                                    cleaning.set(false);
                                    return OresScheduler.done(42);
                                }
                            });
                    untilRegistered(upstream);
                    assertTrue(task.cancel(false));
                    untilRegistered(cleanup);
                    CountDownLatch start = new CountDownLatch(1);
                    var closer = racers.submit(() -> {
                        start.await();
                        scheduler.close();
                        return null;
                    });
                    var completer = racers.submit(() -> {
                        start.await();
                        return cleanup.completeFromRuntime(7);
                    });
                    start.countDown();
                    assertTrue(completer.get(5, TimeUnit.SECONDS));
                    closer.get(5, TimeUnit.SECONDS);
                    assertThrows(CancellationException.class,
                            () -> task.get(5, TimeUnit.SECONDS));
                    assertEquals(1, cleanupEntries.get(), "iteration " + iteration);
                    assertEquals(3, stages.get(), "iteration " + iteration);
                    assertEquals(0, upstream.pendingRuntimeWaiterCount());
                    assertEquals(0, cleanup.pendingRuntimeWaiterCount());
                } finally {
                    scheduler.close();
                }
            }
        } finally {
            racers.shutdownNow();
            assertTrue(racers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
