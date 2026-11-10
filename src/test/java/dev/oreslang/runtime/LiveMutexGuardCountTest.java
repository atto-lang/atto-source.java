package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The interpreter skips its per-statement live-guard scan when
 * OresMutex.mayHaveLiveGuards() is false, so the live count must never
 * under-report. Every path that creates a guard must raise it, and every
 * path that retires one must lower it exactly once.
 *
 * Assertions are on deltas from a baseline, because the count is
 * process-wide and other tests may leave guards behind.
 */
public class LiveMutexGuardCountTest {
    @Test
    void localGuardCountsUntilReleasedAndDoubleReleaseIsIdempotent() {
        var mutex = OresMutex.local(new int[] { 0 });
        long base = OresMutex.liveGuardCount();
        var guard = mutex.lock();
        assertEquals(base + 1, OresMutex.liveGuardCount());
        assertTrue(OresMutex.mayHaveLiveGuards());
        guard.release();
        assertEquals(base, OresMutex.liveGuardCount());
        guard.release();
        assertEquals(base, OresMutex.liveGuardCount(), "second release must not decrement again");
        guard.close();
        assertEquals(base, OresMutex.liveGuardCount());
    }

    @Test
    void contendedLocalTryLockDoesNotCount() {
        var mutex = OresMutex.local(new int[] { 0 });
        long base = OresMutex.liveGuardCount();
        var held = mutex.tryLock().orElseThrow();
        assertEquals(base + 1, OresMutex.liveGuardCount());
        assertThrows(OresMutex.RecursiveLockException.class, mutex::lock);
        assertEquals(base + 1, OresMutex.liveGuardCount());
        held.fail();
        assertEquals(base, OresMutex.liveGuardCount());
        mutex.lock().release();
        assertEquals(base, OresMutex.liveGuardCount());
    }

    @Test
    void sharedGuardCountsUntilReleasedOrFailed() {
        var shared = OresMutex.shared(new int[] { 0 });
        long base = OresMutex.liveGuardCount();
        var guard = shared.lock();
        assertEquals(base + 1, OresMutex.liveGuardCount());
        assertTrue(shared.tryLock().isEmpty(), "contended tryLock must not create a guard");
        assertEquals(base + 1, OresMutex.liveGuardCount());
        guard.release();
        guard.release();
        assertEquals(base, OresMutex.liveGuardCount());

        var failing = shared.lock();
        assertEquals(base + 1, OresMutex.liveGuardCount());
        failing.fail();
        assertEquals(base, OresMutex.liveGuardCount());
        assertTrue(shared.isPoisoned());
    }

    @Test
    void handoffToAsyncWaiterKeepsExactlyOneLiveGuard() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        long base = OresMutex.liveGuardCount();
        var holder = shared.lock();
        try {
            // The waiter must be a different execution domain, or the lock
            // request is rejected as recursive.
            var waiterRef = new java.util.concurrent.atomic.AtomicReference<
                    java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>>();
            Thread other = new Thread(() -> waiterRef.set(shared.lockAsync()));
            other.start();
            other.join(3000);
            var waiter = waiterRef.get();
            assertNotNull(waiter);
            assertFalse(waiter.isDone());
            assertEquals(base + 1, OresMutex.liveGuardCount());
            holder.release();
            var next = waiter.get(3, TimeUnit.SECONDS);
            assertEquals(base + 1, OresMutex.liveGuardCount(), "handoff retires one guard and creates one");
            Thread releaser = new Thread(next::release);
            releaser.start();
            releaser.join(3000);
            assertEquals(base, OresMutex.liveGuardCount());
        } finally {
            holder.release();
        }
    }

    @Test
    void cancelledTaskCleanupRetiresItsGuard() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var never = new OresFuture<Integer>();
        var acquired = new CountDownLatch(1);
        long base = OresMutex.liveGuardCount();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                shared.tryLock().orElseThrow();
                acquired.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertEquals(base + 1, OresMutex.liveGuardCount());
            assertTrue(task.cancel("owner cancelled"));
            assertInstanceOf(TaskHandle.Cancelled.class, task.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(base, OresMutex.liveGuardCount());
        }
    }
}
