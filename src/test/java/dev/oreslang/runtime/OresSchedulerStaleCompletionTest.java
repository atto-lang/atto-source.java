package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Old producer completions must never become a new cleanup await's result. */
final class OresSchedulerStaleCompletionTest {
    @Test
    void abandonedProducerCannotSupplySuspendedCleanupResult() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> upstream = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            AtomicInteger cleanupEntries = new AtomicInteger();
            AtomicInteger observedCleanupValue = new AtomicInteger(-1);
            CountDownLatch enteredCleanup = new CountDownLatch(1);

            OresFuture<Integer> task = scheduler.start(
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cleaning;

                        @Override
                        public boolean cleanupInProgress() { return cleaning; }

                        @Override
                        public void cancellationRequested(
                                OresScheduler.TaskCancelledException ignored) { }

                        @Override
                        public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume input) {
                            if (input.initial()) return OresScheduler.await(upstream);
                            if (!cleaning) {
                                assertInstanceOf(
                                        OresScheduler.TaskCancelledException.class,
                                        input.failure());
                                cleaning = true;
                                cleanupEntries.incrementAndGet();
                                enteredCleanup.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            assertNull(input.failure());
                            observedCleanupValue.set((Integer) input.value());
                            return OresScheduler.done(42);
                        }
                    });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (upstream.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, upstream.pendingRuntimeWaiterCount());

            assertTrue(task.cancel(false));
            assertTrue(enteredCleanup.await(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cleanup.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, cleanup.pendingRuntimeWaiterCount());

            assertTrue(upstream.completeFromRuntime(99),
                    "detaching an awaited continuation must not cancel its producer");
            assertFalse(task.isDone(),
                    "old producer cannot terminate or prematurely resume cleanup");
            assertEquals(-1, observedCleanupValue.get());
            assertEquals(1, cleanupEntries.get());

            assertTrue(cleanup.completeFromRuntime(7));
            assertThrows(CancellationException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(7, observedCleanupValue.get(),
                    "cleanup must receive its own Future result, not upstream's");
            assertEquals(1, cleanupEntries.get());
            assertEquals(0, upstream.pendingRuntimeWaiterCount());
        }
    }
}
