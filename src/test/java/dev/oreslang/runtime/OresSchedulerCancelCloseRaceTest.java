package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Seeded races between a continuation's resume and its cancellation, and
 * between resumes and scheduler close.
 *
 * <p>Cancel vs resume: every task awaits a chain of Futures that producer
 * threads complete, while a racer cancels the task's result at a random
 * moment. Properties: the result settles exactly once; a cancelled result
 * never yields a value; once cancel() has returned, at most the one turn
 * already admitted may still start, and nothing after it; a turn never
 * overlaps another turn of the same task.</p>
 *
 * <p>Close vs resume: producers keep completing awaited Futures while the
 * scheduler closes. Properties: close() returns; every result settles exactly
 * once (value or cancellation); no turn starts after close() returns; a
 * producer completing a Future after close never throws.</p>
 */
@Timeout(120)
final class OresSchedulerCancelCloseRaceTest {

    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) Thread.onSpinWait();
    }

    /** A task that awaits {@code hops} producer-completed Futures and returns their sum. */
    private static final class ChainTask implements OresScheduler.Task<Integer> {
        final int hops;
        final ExecutorService producers;
        final Random random;
        final AtomicInteger turns = new AtomicInteger();
        final AtomicInteger turnsAfterCancel = new AtomicInteger();
        final AtomicBoolean inTurn = new AtomicBoolean();
        final AtomicInteger overlaps;
        final AtomicBoolean cancelReturned = new AtomicBoolean();
        final List<Throwable> producerFailures;
        private int hop;
        private int sum;

        ChainTask(int hops, long seed, ExecutorService producers, AtomicInteger overlaps,
                List<Throwable> producerFailures) {
            this.hops = hops;
            this.random = new Random(seed);
            this.producers = producers;
            this.overlaps = overlaps;
            this.producerFailures = producerFailures;
        }

        @Override
        public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
            if (!inTurn.compareAndSet(false, true)) overlaps.incrementAndGet();
            try {
                turns.incrementAndGet();
                if (cancelReturned.get()) turnsAfterCancel.incrementAndGet();
                if (!resume.initial() && resume.value() instanceof Integer v) sum += v;
                // Mostly short turns; about one in fifty is long enough to still be
                // running when cancel() or close() is called.
                spin(random.nextInt(50) == 0 ? 5_000_000 + random.nextInt(15_000_000) : random.nextInt(40_000));
                if (hop++ == hops) return OresScheduler.done(sum);
                OresFuture<Integer> future = new OresFuture<>();
                int value = 1 + random.nextInt(9);
                long delay = random.nextInt(100_000);
                producers.execute(() -> {
                    spin(delay);
                    try {
                        future.completeFromRuntime(value);
                    } catch (Throwable failure) {
                        producerFailures.add(failure);
                    }
                });
                return OresScheduler.await(future);
            } finally {
                inTurn.set(false);
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {3L, 91L, 2_718L, 65_537L})
    void cancellationRacingResumeSettlesOnceAndStopsFurtherTurns(long seed) throws Exception {
        Random random = new Random(seed);
        int tasks = 500;
        AtomicInteger overlaps = new AtomicInteger();
        List<Throwable> producerFailures = new CopyOnWriteArrayList<>();
        ExecutorService producers = Executors.newFixedThreadPool(4);
        ExecutorService cancellers = Executors.newFixedThreadPool(2);
        try (OresScheduler scheduler = new OresScheduler(4, 8_192)) {
            List<ChainTask> bodies = new ArrayList<>();
            List<OresFuture<Integer>> results = new ArrayList<>();
            List<AtomicInteger> settlements = new ArrayList<>();
            List<AtomicBoolean> cancelWon = new ArrayList<>();
            for (int t = 0; t < tasks; t++) {
                ChainTask body = new ChainTask(1 + random.nextInt(6), random.nextLong(),
                        producers, overlaps, producerFailures);
                OresFuture<Integer> result = scheduler.start(body);
                AtomicInteger settled = new AtomicInteger();
                result.whenCompleteRuntime((value, failure) -> settled.incrementAndGet());
                bodies.add(body);
                results.add(result);
                settlements.add(settled);
                AtomicBoolean won = new AtomicBoolean();
                cancelWon.add(won);
                if (random.nextInt(4) != 0) {
                    long delay = random.nextInt(400_000);
                    cancellers.execute(() -> {
                        spin(delay);
                        if (result.cancel(false)) won.set(true);
                        body.cancelReturned.set(true);
                    });
                }
            }
            cancellers.shutdown();
            assertTrue(cancellers.awaitTermination(30, TimeUnit.SECONDS));
            for (int t = 0; t < tasks; t++) {
                OresFuture<Integer> result = results.get(t);
                ChainTask body = bodies.get(t);
                String where = "seed " + seed + " task " + t;
                try {
                    Integer value = result.get(30, TimeUnit.SECONDS);
                    assertTrue(!cancelWon.get(t).get(), where + ": a cancelled result yielded " + value);
                    assertEquals(body.hops + 1, body.turns.get(), where + ": completed task turn count");
                } catch (CancellationException cancelled) {
                    assertTrue(cancelWon.get(t).get(), where + ": cancelled without a winning cancel");
                }
            }
            // Let any stray resume surface before checking the turn bounds.
            producers.shutdown();
            assertTrue(producers.awaitTermination(30, TimeUnit.SECONDS));
            Thread.sleep(50);
            int cancelledCount = 0;
            for (int t = 0; t < tasks; t++) {
                String where = "seed " + seed + " task " + t;
                assertEquals(1, settlements.get(t).get(), where + ": result settled exactly once");
                if (cancelWon.get(t).get()) {
                    cancelledCount++;
                    assertTrue(bodies.get(t).turnsAfterCancel.get() <= 1,
                            where + ": " + bodies.get(t).turnsAfterCancel.get()
                                    + " turns started after cancel() returned");
                }
            }
            assertTrue(cancelledCount > 0, "seed " + seed + ": the race must cancel some tasks");
            assertEquals(0, overlaps.get(), "seed " + seed + ": turns of one task overlapped");
            assertEquals(List.of(), producerFailures, "seed " + seed);
        } finally {
            producers.shutdownNow();
            cancellers.shutdownNow();
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {8L, 404L, 12_345L})
    void closeRacingResumesSettlesEveryTaskAndRunsNothingAfterward(long seed) throws Exception {
        Random random = new Random(seed);
        int tasks = 400;
        AtomicInteger overlaps = new AtomicInteger();
        List<Throwable> producerFailures = new CopyOnWriteArrayList<>();
        ExecutorService producers = Executors.newFixedThreadPool(4);
        List<ChainTask> bodies = new ArrayList<>();
        List<OresFuture<Integer>> results = new ArrayList<>();
        List<AtomicInteger> settlements = new ArrayList<>();
        OresScheduler scheduler = new OresScheduler(4, 8_192);
        try {
            for (int t = 0; t < tasks; t++) {
                ChainTask body = new ChainTask(2 + random.nextInt(8), random.nextLong(),
                        producers, overlaps, producerFailures);
                OresFuture<Integer> result = scheduler.start(body);
                AtomicInteger settled = new AtomicInteger();
                result.whenCompleteRuntime((value, failure) -> settled.incrementAndGet());
                bodies.add(body);
                results.add(result);
                settlements.add(settled);
            }
            spin(random.nextInt(2_000_000));
        } finally {
            scheduler.close();
        }
        int turnsAtClose = bodies.stream().mapToInt(b -> b.turns.get()).sum();
        long runningAfterClose = bodies.stream().filter(b -> b.inTurn.get()).count();
        // Producers keep completing Futures that now belong to a closed scheduler.
        producers.shutdown();
        assertTrue(producers.awaitTermination(30, TimeUnit.SECONDS));
        Thread.sleep(50);
        int completed = 0;
        for (int t = 0; t < tasks; t++) {
            String where = "seed " + seed + " task " + t;
            OresFuture<Integer> result = results.get(t);
            assertTrue(result.isDone(), where + ": close must settle every task");
            try {
                result.get(1, TimeUnit.SECONDS);
                completed++;
            } catch (CancellationException | ExecutionException closed) {
                // settled by close
            }
            assertEquals(1, settlements.get(t).get(), where + ": settled exactly once");
        }
        assertEquals(turnsAtClose, bodies.stream().mapToInt(b -> b.turns.get()).sum(),
                "seed " + seed + ": a turn started after close() returned");
        assertEquals(0L, runningAfterClose, "seed " + seed + ": close() returned while a turn was still running");
        assertTrue(completed < tasks, "seed " + seed + ": close must interrupt some chains");
        assertEquals(0, overlaps.get(), "seed " + seed);
        assertEquals(List.of(), producerFailures, "seed " + seed + ": late completion must not throw");
    }
}
