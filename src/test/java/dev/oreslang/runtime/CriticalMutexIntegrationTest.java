package dev.oreslang.runtime;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared admission/ownership regression for the runtime logical mutex and the
 * compiler's capacity-one CriticalGate. No guest continuation may be resumed
 * on the producer thread or parked on the scheduler carrier.
 */
final class CriticalMutexIntegrationTest {
    @Test void logicalMutexSharesCriticalGateFifoAndCancellationContract() {
        OresMutex.Critical<int[]> mutex = OresMutex.critical(new int[]{0});
        var owner = mutex.enterAsync().join();
        OresFuture<OresMutex.Guard<int[]>> cancelled = mutex.enterAsync();
        OresFuture<OresMutex.Guard<int[]>> follower = mutex.enterAsync();
        assertTrue(mutex.held());
        assertEquals(2, mutex.queuedCount());
        assertTrue(cancelled.cancel(false));
        assertEquals(1, mutex.queuedCount());
        owner.value()[0] = 17;
        owner.release();
        assertTrue(follower.isDone(), "FIFO handoff must publish the next guard");
        var next = follower.join();
        assertEquals(17, next.value()[0]);
        next.value()[0]++;
        next.release();
        assertTrue(cancelled.isCancelled());
        assertFalse(mutex.held());
        assertEquals(0, mutex.queuedCount());
        assertThrows(IllegalStateException.class, owner::value);
        assertThrows(IllegalStateException.class, next::value);
    }

    @Test void actorStyleSchedulerAwaitFreesItsSingleWorker() {
        OresMutex.Critical<int[]> mutex = OresMutex.critical(new int[]{3});
        var holder = mutex.enterSync();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> waiting = scheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(mutex.enterAsync());
                if (resume.failure() != null) {
                    throw new IllegalStateException("unexpected admission failure", resume.failure());
                }
                @SuppressWarnings("unchecked")
                OresMutex.Guard<int[]> guard = (OresMutex.Guard<int[]>) resume.value();
                try {
                    guard.value()[0] += 4;
                    return OresScheduler.done(guard.value()[0]);
                } finally {
                    guard.release();
                }
            });

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (mutex.queuedCount() == 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(1, mutex.queuedCount());

            OresFuture<Integer> independent = scheduler.start(resume ->
                    OresScheduler.done(99));
            assertEquals(99, independent.join(),
                    "a waiting critical invocation must not consume the only carrier");

            holder.release();
            assertEquals(7, waiting.join());
            assertFalse(mutex.held());
        }
    }

    @Test void criticalGuardFailureReleasesRatherThanPoisoningLikeSharedMutex() {
        OresMutex.Critical<int[]> mutex = OresMutex.critical(new int[]{9});
        var first = mutex.enterSync();
        first.fail();
        assertTrue(first.released());
        assertFalse(mutex.held());
        var next = mutex.enterSync();
        assertEquals(9, next.value()[0]);
        next.release();
    }

    @Test void independentCriticalMutexScopesCanRunConcurrently() {
        var first = OresMutex.critical(new int[]{1});
        var second = OresMutex.critical(new int[]{2});
        var a = first.enterSync();
        var b = second.enterSync();
        assertEquals(1, a.value()[0]);
        assertEquals(2, b.value()[0]);
        a.release();
        b.release();
    }
}
