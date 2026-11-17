package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Seeded properties of continuation scheduling on the locality pool:
 *
 * <ol>
 *   <li>Exactly once: a task with k awaits and c cooperate yields runs exactly
 *       k + c + 1 turns and completes with the value its steps computed.</li>
 *   <li>Serial per task: two turns of one task never overlap, even when its
 *       producer completes the awaited Future while the turn is still on the
 *       carrier.</li>
 *   <li>No inline resumption: no turn ever runs on a producer thread.</li>
 *   <li>Ready-before-await: awaiting an already completed Future still resumes
 *       through the scheduler, exactly once.</li>
 * </ol>
 *
 * <p>Defense in depth, measured: when the carrier pool is made to dispatch
 * every 10th local continuation twice, these properties still hold, because
 * the task runner's phase machine discards a duplicate dispatch. The pool's
 * own conservation property ({@link LocalityCarrierPoolPropertyTest}) is what
 * catches a duplicating pool.</p>
 */
final class OresSchedulerContinuationPropertyTest {

    private record Plan(int[] steps) { } // 0 = await, 1 = await already-complete, 2 = cooperate

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 13L, 404L, 9_001L, 31_337L})
    void everyTaskRunsEachContinuationExactlyOnceAndNeverConcurrently(long seed) throws Exception {
        Random random = new Random(seed);
        final int tasks = 400;
        Set<Thread> producers = ConcurrentHashMap.newKeySet();
        ExecutorService producerPool = Executors.newFixedThreadPool(4, r -> {
            Thread thread = Thread.ofPlatform().daemon(true).name("prop-producer").unstarted(r);
            producers.add(thread);
            return thread;
        });
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger producerTurns = new AtomicInteger();
        AtomicInteger offCarrierTurns = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(4, 4_096)) {
            List<OresFuture<Integer>> results = new ArrayList<>();
            List<Integer> expectedTurns = new ArrayList<>();
            Map<Integer, AtomicInteger> turnsByTask = new ConcurrentHashMap<>();
            for (int t = 0; t < tasks; t++) {
                int[] steps = new int[random.nextInt(6)];
                for (int i = 0; i < steps.length; i++) steps[i] = random.nextInt(3);
                Plan plan = new Plan(steps);
                long taskSeed = random.nextLong();
                int id = t;
                AtomicInteger turns = new AtomicInteger();
                turnsByTask.put(id, turns);
                AtomicBoolean inTurn = new AtomicBoolean();
                expectedTurns.add(steps.length + 1);
                results.add(scheduler.start(new OresScheduler.Task<Integer>() {
                    private int step;
                    private int sum;
                    private final Random own = new Random(taskSeed);

                    @Override
                    public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                        if (!inTurn.compareAndSet(false, true)) overlaps.incrementAndGet();
                        try {
                            turns.incrementAndGet();
                            Thread self = Thread.currentThread();
                            if (producers.contains(self)) producerTurns.incrementAndGet();
                            if (!OresScheduler.isSchedulerCarrierThread()) offCarrierTurns.incrementAndGet();
                            if (!resume.initial() && resume.value() instanceof Integer v) sum += v;
                            if (step == plan.steps().length) return OresScheduler.done(sum);
                            int kind = plan.steps()[step++];
                            if (kind == 2) return OresScheduler.cooperate();
                            OresFuture<Integer> future = new OresFuture<>();
                            int value = own.nextInt(100);
                            if (kind == 1) {
                                future.completeFromRuntime(value); // ready before await
                            } else {
                                long delay = own.nextInt(200);
                                producerPool.execute(() -> {
                                    long until = System.nanoTime() + delay * 1_000;
                                    while (System.nanoTime() < until) Thread.onSpinWait();
                                    future.completeFromRuntime(value);
                                });
                            }
                            return OresScheduler.await(future);
                        } finally {
                            inTurn.set(false);
                        }
                    }
                }));
            }
            for (int t = 0; t < tasks; t++) {
                Integer value = results.get(t).get(30, TimeUnit.SECONDS);
                assertTrue(value != null && value >= 0, "seed " + seed + ": task " + t);
            }
            for (int t = 0; t < tasks; t++) {
                assertEquals(expectedTurns.get(t), turnsByTask.get(t).get(),
                        "seed " + seed + ": task " + t + " turn count");
            }
            assertEquals(0, overlaps.get(), "seed " + seed + ": two turns of one task overlapped");
            assertEquals(0, producerTurns.get(), "seed " + seed + ": a turn ran on a producer thread");
            assertEquals(0, offCarrierTurns.get(), "seed " + seed + ": a turn ran off a scheduler carrier");
        } finally {
            producerPool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {2L, 77L, 1_000L})
    void resumedValuesAreDeliveredToTheTaskThatAwaitedThem(long seed) throws Exception {
        Random random = new Random(seed);
        final int tasks = 300;
        ExecutorService producerPool = Executors.newFixedThreadPool(3);
        try (OresScheduler scheduler = new OresScheduler(3, 4_096)) {
            List<OresFuture<Long>> results = new ArrayList<>();
            List<Long> expected = new ArrayList<>();
            for (int t = 0; t < tasks; t++) {
                int hops = 1 + random.nextInt(5);
                long base = random.nextLong() >>> 8;
                long total = 0;
                for (int h = 0; h < hops; h++) total += base + h;
                expected.add(total);
                results.add(scheduler.start(new OresScheduler.Task<Long>() {
                    private int hop;
                    private long acc;

                    @Override
                    public OresScheduler.Step<Long> resume(OresScheduler.Resume resume) {
                        if (!resume.initial()) acc += (Long) resume.value();
                        if (hop == hops) return OresScheduler.done(acc);
                        long payload = base + hop++;
                        OresFuture<Long> future = new OresFuture<>();
                        producerPool.execute(() -> future.completeFromRuntime(payload));
                        return OresScheduler.await(future);
                    }
                }));
            }
            for (int t = 0; t < tasks; t++) {
                assertEquals(expected.get(t), results.get(t).get(30, TimeUnit.SECONDS),
                        "seed " + seed + ": task " + t + " received another task's value");
            }
        } finally {
            producerPool.shutdownNow();
        }
    }
}
