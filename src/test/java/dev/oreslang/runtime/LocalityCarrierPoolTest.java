package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** CONTINUATION_LOCALITY_V1 rule 5 on the carrier pool itself. */
final class LocalityCarrierPoolTest {
    private static final ThreadFactory DAEMONS = r -> Thread.ofPlatform().daemon(true)
            .name("locality-test-carrier").unstarted(r);

    private static LocalityCarrierPool pool(int carriers, int capacity, long graceNanos) {
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, capacity, DAEMONS, graceNanos, 2);
        assertEquals(carriers, pool.prestartAll());
        return pool;
    }

    private static void close(LocalityCarrierPool pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Runs {@code body} on the pool and returns the slot and thread it ran on. */
    private static Thread runNear(LocalityCarrierPool pool, int preferred, AtomicInteger slot)
            throws InterruptedException {
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        pool.executeNear(() -> {
            ran.set(Thread.currentThread());
            slot.set(pool.currentSlot());
            done.countDown();
        }, preferred);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return ran.get();
    }

    @Test
    void anIdlePreferredCarrierRunsTheContinuation() throws Exception {
        LocalityCarrierPool pool = pool(4, 64, TimeUnit.MILLISECONDS.toNanos(5));
        try {
            AtomicInteger slot = new AtomicInteger(-1);
            Thread first = runNear(pool, LocalityCarrierPool.NO_PREFERENCE, slot);
            int home = slot.get();
            assertTrue(home >= 0 && home < 4);
            for (int i = 0; i < 200; i++) {
                assertSame(first, runNear(pool, home, slot), "turn " + i + " left an idle home carrier");
                assertEquals(home, slot.get());
            }
            assertTrue(pool.stats().localRuns() >= 200);
            assertEquals(0, pool.stats().steals());
        } finally {
            close(pool);
        }
    }

    @Test
    void aBusyPreferredCarrierDoesNotHoldTheContinuationPastTheGrace() throws Exception {
        LocalityCarrierPool pool = pool(2, 64, TimeUnit.MICROSECONDS.toNanos(200));
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger blockerSlot = new AtomicInteger(-1);
            CountDownLatch blocking = new CountDownLatch(1);
            pool.execute(() -> {
                blockerSlot.set(pool.currentSlot());
                blocking.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocking.await(5, TimeUnit.SECONDS));
            AtomicInteger ranOn = new AtomicInteger(-1);
            long start = System.nanoTime();
            runNear(pool, blockerSlot.get(), ranOn);
            assertTrue(ranOn.get() >= 0 && ranOn.get() != blockerSlot.get(),
                    "an idle carrier must take a continuation whose home carrier is busy");
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertEquals(1, pool.stats().steals());
        } finally {
            release.countDown();
            close(pool);
        }
    }

    @Test
    void aBackloggedHomeCarrierSpillsToTheSharedQueue() throws Exception {
        LocalityCarrierPool pool = pool(2, 64, TimeUnit.SECONDS.toNanos(1));
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger home = new AtomicInteger(-1);
            CountDownLatch blocking = new CountDownLatch(1);
            pool.execute(() -> {
                home.set(pool.currentSlot());
                blocking.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocking.await(5, TimeUnit.SECONDS));
            CountDownLatch ran = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) pool.executeNear(ran::countDown, home.get());
            // Slack 2: two wait at home (stealable only after 1 s), three spill
            // to the shared queue and run on the other carrier at once.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (ran.getCount() > 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(2, ran.getCount());
            assertEquals(2, pool.stats().preferredPlacements());
            assertTrue(ran.await(5, TimeUnit.SECONDS), "after the grace the home backlog is stolen");
        } finally {
            release.countDown();
            close(pool);
        }
    }

    @Test
    void aCarrierThatRequeuesItselfCannotStarveSharedWork() throws Exception {
        LocalityCarrierPool pool = pool(1, 64, TimeUnit.SECONDS.toNanos(1));
        AtomicInteger spins = new AtomicInteger();
        CountDownLatch sharedRan = new CountDownLatch(1);
        try {
            // The loop itself submits the shared task at turn 10, so the count is
            // exact: no other thread's scheduling enters the measurement.
            AtomicInteger atSubmit = new AtomicInteger();
            AtomicInteger atRun = new AtomicInteger();
            Runnable shared = () -> {
                atRun.set(spins.get());
                sharedRan.countDown();
            };
            Runnable[] loop = new Runnable[1];
            loop[0] = () -> {
                int turn = spins.incrementAndGet();
                if (turn == 10) {
                    atSubmit.set(turn);
                    pool.execute(shared);
                }
                if (sharedRan.getCount() > 0) pool.executeNear(loop[0], pool.currentSlot());
            };
            pool.execute(loop[0]);
            assertTrue(sharedRan.await(5, TimeUnit.SECONDS));
            int waited = atRun.get() - atSubmit.get();
            assertTrue(waited <= LocalityCarrierPool.SHARED_CHECK_INTERVAL + 1,
                    "shared work waited " + waited + " local turns");
        } finally {
            close(pool);
        }
    }

    @Test
    void producersNeverRunTasksInlineAndQueuesAreBounded() throws Exception {
        LocalityCarrierPool pool = pool(1, 2, 0);
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch blocking = new CountDownLatch(1);
            pool.execute(() -> {
                ranOn.set(Thread.currentThread());
                blocking.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocking.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), ranOn.get());
            pool.execute(() -> { });
            pool.executeNear(() -> { }, 0);
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
            assertThrows(RejectedExecutionException.class, () -> pool.executeNear(() -> { }, 0));
        } finally {
            release.countDown();
            close(pool);
        }
    }

    @Test
    void aThrowingTaskDoesNotRetireItsCarrier() throws Exception {
        AtomicInteger reported = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread thread = Thread.ofPlatform().daemon(true).unstarted(r);
            thread.setUncaughtExceptionHandler((t, e) -> reported.incrementAndGet());
            return thread;
        };
        LocalityCarrierPool pool = new LocalityCarrierPool(1, 8, factory, 0, 2);
        pool.prestartAll();
        try {
            pool.execute(() -> { throw new IllegalStateException("boom"); });
            CountDownLatch after = new CountDownLatch(1);
            pool.execute(after::countDown);
            assertTrue(after.await(5, TimeUnit.SECONDS));
            assertEquals(1, reported.get());
            assertEquals(1, pool.liveCarriers());
        } finally {
            close(pool);
        }
    }

    @Test
    void shutdownDrainsAndShutdownNowReturnsQueuedWork() throws Exception {
        LocalityCarrierPool draining = pool(2, 64, TimeUnit.SECONDS.toNanos(1));
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        draining.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 10; i++) draining.executeNear(ran::incrementAndGet, i % 2);
        draining.shutdown();
        assertThrows(RejectedExecutionException.class, () -> draining.execute(() -> { }));
        release.countDown();
        assertTrue(draining.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(10, ran.get(), "shutdown runs everything already queued");
        assertEquals(0, draining.liveCarriers());

        LocalityCarrierPool stopping = pool(1, 64, TimeUnit.SECONDS.toNanos(1));
        CountDownLatch blocking = new CountDownLatch(1);
        stopping.execute(() -> {
            blocking.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocking.await(5, TimeUnit.SECONDS));
        Runnable a = () -> { };
        Runnable b = () -> { };
        stopping.execute(a);
        stopping.executeNear(b, 0);
        List<Runnable> drained = stopping.shutdownNow();
        assertEquals(2, drained.size());
        assertTrue(drained.contains(a) && drained.contains(b));
        assertTrue(stopping.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(stopping.isTerminated());
    }

    @Test
    void aPoolShutDownBeforeStartTerminatesAndForeignThreadsHaveNoSlot() throws Exception {
        LocalityCarrierPool never = new LocalityCarrierPool(3, 8, DAEMONS);
        assertEquals(LocalityCarrierPool.NO_PREFERENCE, never.currentSlot());
        never.shutdown();
        assertTrue(never.awaitTermination(1, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, never::prestartAll);
        assertFalse(never.isShutdown() && !never.isTerminated());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LocalityCarrierPool(0, 1, DAEMONS));
        assertThrows(IllegalArgumentException.class, () -> new LocalityCarrierPool(1, 0, DAEMONS));
        assertThrows(IllegalArgumentException.class, () -> new LocalityCarrierPool(1, 1, DAEMONS, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> new LocalityCarrierPool(1, 1, DAEMONS, 0, 0));
    }
}
