package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTimersTest {
    @Test
    void timerExpirationReentersOnlyThroughOwningScheduler() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<Thread> initial = new AtomicReference<>();
            AtomicReference<Long> dispatch = new AtomicReference<>();
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    initial.set(Thread.currentThread());
                    dispatch.set(OresScheduler.currentDispatchId());
                    return OresScheduler.await(timers.afterNanos(
                            TimeUnit.MILLISECONDS.toNanos(5)));
                }
                assertSame(scheduler, OresScheduler.current());
                assertSame(initial.get(), Thread.currentThread());
                assertNotEquals(dispatch.get().longValue(),
                        OresScheduler.currentDispatchId());
                assertNull(resume.failure());
                return OresScheduler.done(42);
            });
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
        }
    }

    @Test
    void intervalWaitsForConsumerAndNeverAccumulatesMissedTicks() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresTimers.Interval interval = timers.intervalNanos(
                     TimeUnit.MILLISECONDS.toNanos(2))) {
            assertEquals(1L, interval.next().get(5, TimeUnit.SECONDS).longValue());
            Thread.sleep(25);
            assertEquals(1L, interval.delivered());
            assertEquals(2L, interval.next().get(5, TimeUnit.SECONDS).longValue());
            assertEquals(2L, interval.delivered());
            OresFuture<Long> pending = interval.next();
            assertThrows(IllegalStateException.class, interval::next);
            interval.close();
            assertThrows(CancellationException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals(2L, interval.delivered());
        }
    }

    @Test
    void closeCancelsOutstandingTimersAndReleasesCapacity() throws Exception {
        OresTimers timers = new OresTimers();
        OresFuture<Void> timer = timers.afterNanos(TimeUnit.HOURS.toNanos(2));
        assertEquals(1, timers.activeTimerCount());
        timers.close();
        assertThrows(CancellationException.class,
                () -> timer.get(5, TimeUnit.SECONDS));
        assertEquals(0, timers.activeTimerCount());
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> timers.afterNanos(1));
    }

    @Test
    void invalidAndCancelledTimersNeverCreateGuestWork() throws Exception {
        try (OresTimers timers = new OresTimers()) {
            assertThrows(IllegalArgumentException.class,
                    () -> timers.afterNanos(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> timers.intervalNanos(0));
            OresFuture<Void> timer = timers.afterNanos(TimeUnit.HOURS.toNanos(1));
            assertTrue(timer.cancel(false));
            assertThrows(CancellationException.class,
                    () -> timer.get(5, TimeUnit.SECONDS));
            assertEquals(0, timers.activeTimerCount());
        }
    }
}
