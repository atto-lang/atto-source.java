package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Idle soft lanes take a turn that is queued behind a busy lane once it has
 * waited the steal grace; the turn still runs exactly once.
 */
final class ActorLaneIdleStealTest {
    private static final int CAPACITY = 1_024;

    private static ActorAffinityDispatcher lanes(int parallelism, long graceNanos,
            List<Thread> laneThreads) {
        return new ActorAffinityDispatcher(parallelism, CAPACITY, new int[0], lane -> {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(CAPACITY), r -> {
                        Thread thread = Thread.ofPlatform().daemon(true)
                                .name("steal-test-lane-" + lane).unstarted(r);
                        synchronized (laneThreads) {
                            while (laneThreads.size() <= lane) laneThreads.add(null);
                            laneThreads.set(lane, thread);
                        }
                        return thread;
                    });
            pool.prestartAllCoreThreads();
            return pool;
        }, false, graceNanos);
    }

    private static void close(ExecutorService lanes) throws InterruptedException {
        lanes.shutdownNow();
        assertTrue(lanes.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static Runnable blocker(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void aTurnQueuedBehindABlockedLaneRunsOnAnIdleLaneAfterTheGrace() throws Exception {
        List<Thread> threads = new ArrayList<>();
        ActorAffinityDispatcher lanes = lanes(2, TimeUnit.MICROSECONDS.toNanos(200), threads);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            lanes.executeOnLane(0, blocker(entered, release));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch ran = new CountDownLatch(1);
            long start = System.nanoTime();
            lanes.executeOnLane(0, () -> {
                runs.incrementAndGet();
                ranOn.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(5, TimeUnit.SECONDS), "turn stranded behind a blocked lane");
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertSame(threads.get(1), ranOn.get(), "an idle lane takes the turn");
            assertEquals(1, lanes.steals());
            release.countDown();
            Thread.sleep(50); // the home lane now reaches the original entry
            assertEquals(1, runs.get(), "the original queue entry must not run it again");
            assertEquals(CAPACITY, lanes.availableQueueSlots());
            assertEquals(0, lanes.outstandingTurns());
        } finally {
            release.countDown();
            close(lanes);
        }
    }

    @Test
    void aTurnWhoseHomeLaneIsIdleStaysHomeAndSendsNoProbe() throws Exception {
        List<Thread> threads = new ArrayList<>();
        ActorAffinityDispatcher lanes = lanes(4, TimeUnit.MICROSECONDS.toNanos(50), threads);
        try {
            for (int i = 0; i < 200; i++) {
                AtomicReference<Thread> ranOn = new AtomicReference<>();
                CountDownLatch ran = new CountDownLatch(1);
                lanes.executeOnLane(2, () -> {
                    ranOn.set(Thread.currentThread());
                    ran.countDown();
                });
                assertTrue(ran.await(5, TimeUnit.SECONDS));
                assertSame(threads.get(2), ranOn.get());
            }
            assertEquals(0, lanes.steals());
        } finally {
            close(lanes);
        }
    }

    @Test
    void stealingDisabledLeavesTheTurnOnItsHomeLane() throws Exception {
        List<Thread> threads = new ArrayList<>();
        ActorAffinityDispatcher lanes = lanes(2, -1L, threads);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            lanes.executeOnLane(0, blocker(entered, release));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            lanes.executeOnLane(0, () -> {
                ranOn.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(!ran.await(100, TimeUnit.MILLISECONDS), "ran before its home lane was free");
            release.countDown();
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertSame(threads.get(0), ranOn.get());
            assertEquals(0, lanes.steals());
        } finally {
            release.countDown();
            close(lanes);
        }
    }

    @Test
    void aProbeWhoseLaneGetsItsOwnWorkLeavesTheTurnHome() throws Exception {
        List<Thread> threads = new ArrayList<>();
        // A long grace makes the race deterministic: lane 1's own turn arrives
        // while its probe is still waiting.
        ActorAffinityDispatcher lanes = lanes(2, TimeUnit.MILLISECONDS.toNanos(300), threads);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            lanes.executeOnLane(0, blocker(entered, release));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            lanes.executeOnLane(0, () -> {
                ranOn.set(Thread.currentThread());
                ran.countDown();
            });
            CountDownLatch ownRan = new CountDownLatch(1);
            lanes.executeOnLane(1, ownRan::countDown);
            assertTrue(ownRan.await(5, TimeUnit.SECONDS));
            Thread.sleep(400); // past the grace
            assertEquals(1, ran.getCount(), "a lane that was given work must not steal");
            release.countDown();
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertSame(threads.get(0), ranOn.get());
            assertEquals(0, lanes.steals());
        } finally {
            release.countDown();
            close(lanes);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 8L, 64L, 2_048L, 99_991L})
    void everyTurnRunsExactlyOnceAndAccountingReturnsToZero(long seed) throws Exception {
        Random random = new Random(seed);
        final int parallelism = 4;
        final int turns = 3_000;
        ActorAffinityDispatcher lanes = lanes(parallelism, TimeUnit.MICROSECONDS.toNanos(30),
                new ArrayList<>());
        AtomicIntegerArray runs = new AtomicIntegerArray(turns);
        try {
            for (int i = 0; i < turns; i++) {
                int id = i;
                long spin = random.nextInt(10) == 0 ? random.nextInt(500_000) : random.nextInt(2_000);
                Runnable turn = () -> {
                    runs.incrementAndGet(id);
                    long until = System.nanoTime() + spin;
                    while (System.nanoTime() < until) Thread.onSpinWait();
                };
                int home = random.nextInt(parallelism);
                while (true) {
                    try {
                        lanes.executeOnLane(home, turn);
                        break;
                    } catch (java.util.concurrent.RejectedExecutionException full) {
                        Thread.sleep(1); // bounded queue: back off, as a producer would
                    }
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (lanes.outstandingTurns() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(20); // late duplicates would land here
            for (int i = 0; i < turns; i++) {
                if (runs.get(i) != 1) fail("seed " + seed + ": turn " + i + " ran " + runs.get(i) + " times");
            }
            assertEquals(0, lanes.outstandingTurns(), "seed " + seed);
            assertEquals(CAPACITY, lanes.availableQueueSlots(), "seed " + seed + ": leaked queue slots");
        } finally {
            close(lanes);
        }
    }

    @Test
    void shutdownNowReturnsEachUnrunTurnOnceAndIgnoresProbes() throws Exception {
        List<Thread> threads = new ArrayList<>();
        ActorAffinityDispatcher lanes = lanes(3, TimeUnit.SECONDS.toNanos(5), threads);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        lanes.executeOnLane(0, blocker(entered, release));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        AtomicInteger ran = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Runnable turn = ran::incrementAndGet;
            queued.add(turn);
            lanes.executeOnLane(0, turn); // each sends a probe to an idle lane
        }
        List<Runnable> abandoned = lanes.shutdownNow();
        release.countDown();
        assertTrue(lanes.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(6, abandoned.size(), "every unrun turn exactly once, no probe objects");
        for (Runnable turn : queued) assertTrue(abandoned.contains(turn));
        assertEquals(0, ran.get());
        assertNotSame(null, threads.get(0));
    }
}
