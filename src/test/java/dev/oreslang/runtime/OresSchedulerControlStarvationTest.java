package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Starvation bound for newly submitted CONTROL work under a continuation flood.
 *
 * <p>Continuations prefer the carrier that ran their previous turn
 * (CONTINUATION_LOCALITY_V1 rule 5), so a busy task that keeps awaiting ready
 * Futures re-enters its own carrier's local deque. Work submitted from outside
 * (a new root task, a Mailman dispatch) goes to the shared queue. This proves,
 * through the real OresScheduler and TaskRunner hinting, that such work starts
 * in FIFO order: after at most one turn of each older runnable task plus a
 * small per-carrier slack. The bound is counted in turns, not wall time.</p>
 */
@Timeout(60)
final class OresSchedulerControlStarvationTest {

    /** A task that awaits an already completed Future forever, until told to stop. */
    private static OresScheduler.Task<Integer> flood(AtomicBoolean stop, AtomicLong floodTurns) {
        return resume -> {
            floodTurns.incrementAndGet();
            // 200 us per turn, so the host thread's own start() call (before the
            // task is even queued) spans well under one flood turn per carrier.
            long until = System.nanoTime() + 200_000;
            while (System.nanoTime() < until) Thread.onSpinWait();
            if (stop.get()) return OresScheduler.done(0);
            OresFuture<Integer> ready = new OresFuture<>();
            ready.completeFromRuntime(1);
            return OresScheduler.await(ready);
        };
    }

    @ParameterizedTest(name = "{0} flood tasks")
    @ValueSource(ints = {2, 8, 64})
    void freshlySubmittedWorkStartsWithinABoundedNumberOfFloodTurns(int floodTasks) throws Exception {
        int carriers = 2;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong floodTurns = new AtomicLong();
        try (OresScheduler scheduler = new OresScheduler(carriers, 16_384)) {
            List<OresFuture<Integer>> flooders = new ArrayList<>();
            for (int i = 0; i < floodTasks; i++) flooders.add(scheduler.start(flood(stop, floodTurns)));
            // Let the flood settle onto its carriers' local deques.
            long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
            while (System.nanoTime() < settle) Thread.onSpinWait();

            long worst = 0;
            for (int probe = 0; probe < 40; probe++) {
                long submittedAt = floodTurns.get();
                AtomicLong startedAt = new AtomicLong(-1);
                OresFuture<Integer> fresh = scheduler.start(resume -> {
                    startedAt.compareAndSet(-1, floodTurns.get());
                    return OresScheduler.done(1);
                });
                fresh.get(10, TimeUnit.SECONDS);
                worst = Math.max(worst, startedAt.get() - submittedAt);
            }
            stop.set(true);
            for (OresFuture<Integer> flooder : flooders) flooder.get(10, TimeUnit.SECONDS);

            // FIFO across the shared queue and the local deques: fresh work waits
            // only for work submitted before it, which is at most one turn of every
            // flood task plus each carrier's local slack and its in-flight turn.
            // Before submission-order dequeue this was ~61 turns per older item.
            long bound = floodTasks + (long) carriers * (LocalityCarrierPool.DEFAULT_LOCAL_SLACK + 2);
            assertTrue(worst <= bound, floodTasks + " flood tasks: fresh work waited " + worst
                    + " flood turns, bound " + bound);
            assertTrue(floodTurns.get() > 40L * floodTasks / 8, "the flood really ran: " + floodTurns.get());
        }
    }
}
