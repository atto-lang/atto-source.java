package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The watchdog is advisory only: no guest callbacks, interrupts, actor
 * re-entrancy or forced cancellation are permitted from its observer thread.
 */
@Timeout(15)
final class SchedulerCooperationWatchdogTest {
    @Test
    void thresholdIsStrictlyFourMillisecondsAndOnlyActiveTurnsAreCounted() {
        AtomicLong observed = new AtomicLong();
        SchedulerCooperationWatchdog.Lease lease =
                new SchedulerCooperationWatchdog.Lease(observed);
        lease.enterAtForTest(100);
        assertFalse(lease.inspect(4_000_099));
        assertFalse(lease.cooperationRequested());
        assertTrue(lease.inspect(4_000_100));
        assertTrue(lease.cooperationRequested());
        assertFalse(lease.inspect(40_000_000),
                "one slow dispatch is one watchdog observation");
        assertEquals(1L, observed.get());

        lease.leaveForTest();
        assertFalse(lease.cooperationRequested());
        assertFalse(lease.inspect(100_000_000),
                "a parked task cannot be counted as a greedy carrier");
        lease.enterAtForTest(100_000_000);
        assertFalse(lease.inspect(103_999_999));
        assertTrue(lease.inspect(104_000_000));
        assertEquals(2L, observed.get());
        lease.leaveForTest();
    }

    @Test
    void otherCarrierCanRequestButCannotPreemptBlockedTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch allowReturn = new CountDownLatch(1);
            AtomicBoolean sawPressure = new AtomicBoolean();
            OresFuture<Integer> task = scheduler.start(resume -> {
                assertTrue(resume.initial());
                entered.countDown();
                if (!allowReturn.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release never arrived");
                }
                sawPressure.set(OresScheduler.watchdogCooperationRequested());
                return OresScheduler.done(5);
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (scheduler.greedyDispatchObservations() == 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
                assertEquals(1, scheduler.greedyDispatchObservations());
                assertFalse(task.isDone(),
                        "watchdog never executes guest code or interrupts the carrier");
            } finally {
                allowReturn.countDown();
            }
            assertEquals(5, task.get(3, TimeUnit.SECONDS));
            assertTrue(sawPressure.get());
        }
    }

    @Test
    void suspendedFutureTimeDoesNotCountAsGreedyDispatch() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> awaited = new OresFuture<>();
            CountDownLatch parked = new CountDownLatch(1);
            AtomicLong turns = new AtomicLong();
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    parked.countDown();
                    return OresScheduler.await(awaited);
                }
                assertFalse(OresScheduler.watchdogCooperationRequested());
                return OresScheduler.done(9);
            });
            assertTrue(parked.await(3, TimeUnit.SECONDS));
            Thread.sleep(30);
            assertEquals(0, scheduler.greedyDispatchObservations(),
                    "a Future's parked time is not active carrier CPU time");
            awaited.completeFromRuntime(9);
            assertEquals(9, task.get(3, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
        }
    }
}
