package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerCleanupAwareCancellationTest {
    @Test
    void cancellationRemainsPendingUntilSuspendedCleanupFinishes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            CountDownLatch waiting = new CountDownLatch(1);
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(
                    new OresScheduler.CleanupAwareTask<>() {
                        @Override
                        public OresScheduler.Step<Integer> resume(OresScheduler.Resume event) {
                            int turn = turns.getAndIncrement();
                            if (turn == 0) {
                                assertTrue(event.initial());
                                waiting.countDown();
                                return OresScheduler.await(source);
                            }
                            if (turn == 1) {
                                assertInstanceOf(CancellationException.class, event.failure());
                                cleanupStarted.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            assertEquals(2, turn);
                            assertNull(event.failure());
                            return OresScheduler.done(42);
                        }
                    });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "cleanup has not finished");
            assertFalse(result.isCancelled());
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(source.isCancelled(), "shared producers must not be cancelled");
            cleanup.completeFromRuntime(1);
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(3, turns.get());
            assertTrue(result.isCancelled());
        }
    }

    @Test
    void cancellationDuringRunningTurnWinsOnlyAfterCarrierUnwinds() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return OresScheduler.done(99);
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertFalse(result.isDone());
            assertFalse(result.cancel(false));
            release.countDown();
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
        }
    }
}
