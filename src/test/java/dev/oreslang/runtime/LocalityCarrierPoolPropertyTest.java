package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Randomized, seeded properties of {@link LocalityCarrierPool}. Every seed is
 * reproducible: a failure message names the seed that produced it.
 *
 * <p>Properties:</p>
 * <ol>
 *   <li>Conservation: every accepted task runs exactly once, only on a pool
 *       carrier, never on the thread that submitted it, whatever the mix of
 *       valid, stale, out-of-range and absent carrier hints.</li>
 *   <li>Shutdown accounting: after {@code shutdownNow}, tasks that ran plus
 *       tasks returned as drained equal tasks accepted, and no drained task
 *       ever runs.</li>
 *   <li>Bounded queues: with every carrier held, exactly {@code capacity}
 *       submissions are accepted regardless of hints; all of them run once
 *       the carriers are released.</li>
 *   <li>No idle reservation: a continuation hinted at a busy carrier runs
 *       on an idle carrier within the grace plus scheduling slack.</li>
 *   <li>Locality: independent self-requeueing chains stay on their carrier.</li>
 * </ol>
 */
final class LocalityCarrierPoolPropertyTest {
    private static final long SEEDS_TIMEOUT_SECONDS = 30;

    private static ThreadFactory carriers(Map<Thread, Boolean> registry) {
        return r -> {
            Thread thread = Thread.ofPlatform().daemon(true).name("prop-carrier").unstarted(r);
            registry.put(thread, Boolean.TRUE);
            return thread;
        };
    }

    private static void close(LocalityCarrierPool pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "pool did not terminate");
    }

    /** A random hint: a valid slot, an out-of-range slot, or no preference. */
    private static int randomHint(Random random, int carriers) {
        return switch (random.nextInt(4)) {
            case 0 -> LocalityCarrierPool.NO_PREFERENCE;
            case 1 -> carriers + random.nextInt(8); // stale or foreign slot
            case 2 -> -2 - random.nextInt(8);
            default -> random.nextInt(carriers);
        };
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 7L, 42L, 1_234_567L, 0xC0FFEEL, -9L})
    void everyAcceptedTaskRunsExactlyOnceOnACarrierNeverItsSubmitter(long seed) throws Exception {
        final int carriers = 4;
        final int producers = 6;
        final int perProducer = 3_000;
        final int nestedEvery = 7; // some tasks submit follow-ups from a carrier
        final int total = producers * perProducer * 2; // upper bound incl. nested
        Map<Thread, Boolean> carrierThreads = new ConcurrentHashMap<>();
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, 1 << 16,
                carriers(carrierThreads), TimeUnit.MICROSECONDS.toNanos(20), 2);
        pool.prestartAll();
        AtomicIntegerArray runs = new AtomicIntegerArray(total);
        AtomicInteger nextId = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        Map<Integer, Thread> submitter = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                Random random = new Random(seed * 31 + p);
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perProducer; i++) {
                        submit(pool, random, carriers, runs, nextId, accepted, violations,
                                submitter, carrierThreads, nestedEvery);
                    }
                }));
            }
            go.countDown();
            for (Thread t : threads) t.join(TimeUnit.SECONDS.toMillis(SEEDS_TIMEOUT_SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEEDS_TIMEOUT_SECONDS);
            while (sum(runs, nextId.get()) < accepted.get() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            int issued = nextId.get();
            assertEquals(accepted.get(), issued, "seed " + seed + ": a submission was rejected");
            for (int id = 0; id < issued; id++) {
                if (runs.get(id) != 1) {
                    fail("seed " + seed + ": task " + id + " ran " + runs.get(id) + " times");
                }
            }
            assertEquals(0, violations.get(),
                    "seed " + seed + ": a task ran off-carrier or on its submitter");
            var stats = pool.stats();
            assertEquals(issued, stats.localRuns() + stats.sharedRuns() + stats.steals(),
                    "seed " + seed + ": every dequeue is counted exactly once");
            assertEquals(issued, stats.preferredPlacements() + stats.sharedPlacements(),
                    "seed " + seed + ": every placement is counted exactly once");
        } finally {
            close(pool);
        }
    }

    private static void submit(LocalityCarrierPool pool, Random random, int carriers,
            AtomicIntegerArray runs, AtomicInteger nextId, AtomicInteger accepted,
            AtomicInteger violations, Map<Integer, Thread> submitter,
            Map<Thread, Boolean> carrierThreads, int nestedEvery) {
        int id = nextId.getAndIncrement();
        Thread self = Thread.currentThread();
        submitter.put(id, self);
        boolean nest = id % nestedEvery == 0;
        long nestSeed = random.nextLong();
        pool.executeNear(() -> {
            Thread runner = Thread.currentThread();
            if (runner == submitter.get(id) || !carrierThreads.containsKey(runner)
                    || pool.currentSlot() < 0) {
                violations.incrementAndGet();
            }
            runs.incrementAndGet(id);
            if (nest) {
                // A carrier submitting follow-up work (a child task or resumed
                // continuation) with a hint at itself or elsewhere.
                Random nested = new Random(nestSeed);
                int hint = nested.nextBoolean() ? pool.currentSlot() : randomHint(nested, carriers);
                int child = nextId.getAndIncrement();
                submitter.put(child, runner);
                pool.executeNear(() -> {
                    if (!carrierThreads.containsKey(Thread.currentThread())) {
                        violations.incrementAndGet();
                    }
                    runs.incrementAndGet(child);
                }, hint);
                accepted.incrementAndGet();
            }
        }, randomHint(random, carriers));
        accepted.incrementAndGet();
    }

    private static int sum(AtomicIntegerArray runs, int upTo) {
        int sum = 0;
        for (int i = 0; i < Math.min(upTo, runs.length()); i++) sum += runs.get(i);
        return sum;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {3L, 11L, 99L, 2026L})
    void shutdownNowAccountsForEveryAcceptedTask(long seed) throws Exception {
        final int carriers = 3;
        Random random = new Random(seed);
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, 4_096,
                carriers(new ConcurrentHashMap<>()), TimeUnit.MILLISECONDS.toNanos(1), 2);
        pool.prestartAll();
        Map<Runnable, Boolean> ran = Collections.synchronizedMap(new IdentityHashMap<>());
        List<Runnable> acceptedTasks = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicInteger ranAfterStop = new AtomicInteger();
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 20_000; i++) {
                Runnable[] self = new Runnable[1];
                long spin = random.nextInt(2_000);
                self[0] = () -> {
                    if (stopping.get() && ran.containsKey(self[0])) ranAfterStop.incrementAndGet();
                    ran.put(self[0], Boolean.TRUE);
                    long until = System.nanoTime() + spin;
                    while (System.nanoTime() < until) Thread.onSpinWait();
                };
                try {
                    pool.executeNear(self[0], randomHint(random, carriers));
                    acceptedTasks.add(self[0]);
                } catch (RejectedExecutionException closed) {
                    return;
                }
            }
        });
        Thread.sleep(5 + random.nextInt(20));
        stopping.set(true);
        List<Runnable> drained = pool.shutdownNow();
        producer.join(10_000);
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        Map<Runnable, Boolean> drainedSet = new IdentityHashMap<>();
        for (Runnable r : drained) {
            assertTrue(drainedSet.put(r, Boolean.TRUE) == null, "seed " + seed + ": drained twice");
        }
        int both = 0;
        for (Runnable r : drained) if (ran.containsKey(r)) both++;
        assertEquals(0, both, "seed " + seed + ": a drained task also ran");
        assertEquals(0, ranAfterStop.get(), "seed " + seed + ": a task ran twice");
        Thread.sleep(50); // nothing may run once terminated
        assertEquals(acceptedTasks.size(), ran.size() + drained.size(),
                "seed " + seed + ": accepted != ran + drained");
    }

    @org.junit.jupiter.api.Test
    void shutdownNowReturnsWorkQueuedLocallyOnEveryCarrierAndShared() throws Exception {
        final int carriers = 4;
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, 256,
                carriers(new ConcurrentHashMap<>()), TimeUnit.SECONDS.toNanos(5), 2);
        pool.prestartAll();
        CountDownLatch held = new CountDownLatch(carriers);
        CountDownLatch never = new CountDownLatch(1);
        int[] slotOf = new int[carriers];
        AtomicInteger heldCount = new AtomicInteger();
        for (int i = 0; i < carriers; i++) {
            pool.execute(() -> {
                slotOf[heldCount.getAndIncrement()] = pool.currentSlot();
                held.countDown();
                try {
                    never.await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(held.await(5, TimeUnit.SECONDS));
        AtomicInteger ran = new AtomicInteger();
        Map<Runnable, Boolean> queued = new IdentityHashMap<>();
        for (int slot = 0; slot < carriers; slot++) {
            for (int k = 0; k < 2; k++) {
                Runnable local = ran::incrementAndGet;
                pool.executeNear(local, slot);
                queued.put(local, Boolean.TRUE);
            }
        }
        for (int k = 0; k < 5; k++) {
            Runnable shared = ran::incrementAndGet;
            pool.execute(shared);
            queued.put(shared, Boolean.TRUE);
        }
        assertEquals(carriers * 2, pool.stats().preferredPlacements(),
                "every carrier holds a full local queue before the stop");
        List<Runnable> drained = pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(queued.size(), drained.size(), "local or shared work lost by shutdownNow");
        for (Runnable r : drained) assertTrue(queued.containsKey(r));
        assertEquals(0, ran.get(), "drained work also ran");
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {5L, 17L, 123L})
    void queuesAreBoundedForAnyMixOfHints(long seed) throws Exception {
        final int carriers = 3;
        final int capacity = 64;
        Random random = new Random(seed);
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, capacity,
                carriers(new ConcurrentHashMap<>()), TimeUnit.MILLISECONDS.toNanos(1), 2);
        pool.prestartAll();
        CountDownLatch held = new CountDownLatch(carriers);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < carriers; i++) {
                pool.execute(() -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(held.await(5, TimeUnit.SECONDS));
            AtomicInteger ran = new AtomicInteger();
            int accepted = 0;
            int rejected = 0;
            for (int i = 0; i < capacity * 3; i++) {
                try {
                    pool.executeNear(ran::incrementAndGet, randomHint(random, carriers));
                    accepted++;
                } catch (RejectedExecutionException full) {
                    rejected++;
                }
            }
            assertEquals(capacity, accepted, "seed " + seed);
            assertEquals(capacity * 2, rejected, "seed " + seed);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (ran.get() < capacity && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(capacity, ran.get(), "seed " + seed + ": queued work lost");
            // Capacity is released by dequeues: a full queue's worth fits again.
            CountDownLatch again = new CountDownLatch(capacity);
            for (int i = 0; i < capacity; i++) {
                pool.executeNear(again::countDown, randomHint(random, carriers));
            }
            assertTrue(again.await(10, TimeUnit.SECONDS), "seed " + seed);
        } finally {
            release.countDown();
            close(pool);
        }
    }

    @ParameterizedTest(name = "grace {0} us")
    @ValueSource(longs = {50L, 500L, 2_000L})
    void aContinuationNeverWaitsLongPastTheGraceWhileACarrierIsIdle(long graceMicros)
            throws Exception {
        final int carriers = 4;
        long grace = TimeUnit.MICROSECONDS.toNanos(graceMicros);
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, 1_024,
                carriers(new ConcurrentHashMap<>()), grace, 2);
        pool.prestartAll();
        CountDownLatch release = new CountDownLatch(1);
        try {
            AtomicInteger busySlot = new AtomicInteger(-1);
            CountDownLatch holding = new CountDownLatch(1);
            pool.execute(() -> {
                busySlot.set(pool.currentSlot());
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            long worst = 0;
            for (int i = 0; i < 200; i++) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicLong ranAt = new AtomicLong();
                AtomicInteger ranOn = new AtomicInteger(-1);
                long submitted = System.nanoTime();
                pool.executeNear(() -> {
                    ranAt.set(System.nanoTime());
                    ranOn.set(pool.currentSlot());
                    done.countDown();
                }, busySlot.get());
                assertTrue(done.await(5, TimeUnit.SECONDS), "continuation stranded behind a busy carrier");
                assertFalse(ranOn.get() == busySlot.get(), "ran on the carrier that is still held");
                worst = Math.max(worst, ranAt.get() - submitted);
            }
            // Grace plus generous scheduling slack for a loaded CI host.
            long bound = grace + TimeUnit.MILLISECONDS.toNanos(250);
            assertTrue(worst <= bound, "worst wait " + worst / 1_000 + " us exceeds "
                    + bound / 1_000 + " us");
        } finally {
            release.countDown();
            close(pool);
        }
    }

    @ParameterizedTest(name = "{0} chains")
    @ValueSource(ints = {1, 2, 4})
    void independentChainsKeepTheirCarriers(int chains) throws Exception {
        final int carriers = 4;
        final int hops = 2_000;
        LocalityCarrierPool pool = new LocalityCarrierPool(carriers, 1_024,
                carriers(new ConcurrentHashMap<>()), TimeUnit.MILLISECONDS.toNanos(2), 2);
        pool.prestartAll();
        CountDownLatch finished = new CountDownLatch(chains);
        AtomicInteger migrations = new AtomicInteger();
        try {
            for (int c = 0; c < chains; c++) {
                int[] remaining = {hops};
                int[] last = {-1};
                Runnable[] step = new Runnable[1];
                step[0] = () -> {
                    int slot = pool.currentSlot();
                    if (last[0] >= 0 && slot != last[0]) migrations.incrementAndGet();
                    last[0] = slot;
                    if (--remaining[0] == 0) {
                        finished.countDown();
                        return;
                    }
                    // Like an await whose producer completes immediately.
                    pool.executeNear(step[0], slot);
                };
                pool.execute(step[0]);
            }
            assertTrue(finished.await(30, TimeUnit.SECONDS));
            int total = chains * (hops - 1);
            assertTrue(migrations.get() * 100 <= total,
                    migrations.get() + " of " + total + " hops migrated (> 1%)");
        } finally {
            close(pool);
        }
    }
}
