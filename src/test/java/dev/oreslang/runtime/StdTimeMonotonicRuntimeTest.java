package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact physical monotonic observations: this suite does not claim that guest
 * `await time.sleep()` or `sleep_until` have compiler lowering yet.
 */
@Timeout(30)
final class StdTimeMonotonicRuntimeTest {
    @Test
    void nativeTimerNeverSettlesBeforeItsMonotonicDuration() throws Exception {
        try (OresTimers timers = new OresTimers()) {
            for (long delayNs : new long[] {
                    TimeUnit.MILLISECONDS.toNanos(1),
                    TimeUnit.MILLISECONDS.toNanos(10),
                    TimeUnit.MILLISECONDS.toNanos(35) }) {
                long startNs = System.nanoTime();
                AtomicLong settledAtNs = new AtomicLong(Long.MIN_VALUE);
                CountDownLatch noticed = new CountDownLatch(1);
                OresFuture<Void> timeout = timers.afterNanos(delayNs);
                timeout.whenCompleteRuntime((value, error) -> {
                    settledAtNs.set(System.nanoTime());
                    noticed.countDown();
                });
                assertTrue(noticed.await(5, TimeUnit.SECONDS));
                assertTrue(settledAtNs.get() - startNs >= delayNs,
                        "monotonic timer settled early at " + delayNs + "ns");
                assertNull(timeout.get(5, TimeUnit.SECONDS));
            }
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void cancelledSleepCannotCompleteOrLeakAdmissionSlots() throws Exception {
        try (OresTimers timers = new OresTimers()) {
            int slots = OresTimers.availableGlobalTimerSlots();
            OresFuture<Void> one = timers.sleepNanos(TimeUnit.HOURS.toNanos(1));
            assertEquals(1, timers.activeTimerCount());
            assertTrue(one.cancel(false));
            assertTrue(one.isCancelled());
            // The completion callback that releases admission is registered
            // before the timer is armed and runs synchronously at settlement.
            assertEquals(0, timers.activeTimerCount());
            assertEquals(slots, OresTimers.availableGlobalTimerSlots());
        }
    }

    @Test
    void zeroDelayWaitStillRequiresSchedulerReentry() throws Exception {
        try (OresTimers timers = new OresTimers(); OresScheduler scheduler = new OresScheduler(1)) {
            AtomicLong firstDispatch = new AtomicLong();
            java.util.concurrent.atomic.AtomicInteger turns =
                    new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> sleeper = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    return OresScheduler.sleepNanos(timers, 0);
                }
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                assertSame(scheduler, OresScheduler.current());
                return OresScheduler.done(7);
            });
            assertEquals(7, sleeper.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
        }
    }
}
