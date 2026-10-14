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
            assertTrue(interval.cancel());
            assertFalse(interval.cancel());
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

    @Test
    void ownerCloseRevokesIdleAndPendingIntervalCapabilities() throws Exception {
        OresTimers timers = new OresTimers();
        OresTimers.Interval idle = timers.intervalNanos(1);
        OresTimers.Interval pending = timers.intervalNanos(
                TimeUnit.HOURS.toNanos(1));
        assertEquals(2, timers.activeIntervalCount());
        OresFuture<Long> waiting = pending.next();
        timers.close();
        assertEquals(0, timers.activeTimerCount());
        assertEquals(0, timers.activeIntervalCount());
        assertFalse(idle.cancel());
        assertFalse(pending.cancel());
        assertThrows(CancellationException.class,
                () -> idle.next().get(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class,
                () -> waiting.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeRacingWithIntervalNextNeverLeavesPendingTimers() throws Exception {
        for (int i = 0; i < 100; ++i) {
            OresTimers timers = new OresTimers();
            OresTimers.Interval interval = timers.intervalNanos(
                    TimeUnit.HOURS.toNanos(1));
            AtomicReference<OresFuture<Long>> result = new AtomicReference<>();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread requester = Thread.ofPlatform().start(() -> {
                try {
                    result.set(interval.next());
                } catch (Throwable failure) {
                    problem.set(failure);
                }
            });
            timers.close();
            requester.join();
            assertNull(problem.get(), "next() unexpectedly threw during owner shutdown");
            assertThrows(CancellationException.class,
                    () -> result.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, timers.activeTimerCount());
            assertEquals(0, timers.activeIntervalCount());
        }
    }

    @Test
    void globalTimerReservationReturnsAfterCompletionAndCancellation() throws Exception {
        int before = OresTimers.availableGlobalTimerSlots();
        try (OresTimers timers = new OresTimers()) {
            OresFuture<Void> waiting = timers.afterNanos(
                    TimeUnit.HOURS.toNanos(1));
            assertEquals(before - 1, OresTimers.availableGlobalTimerSlots());
            assertTrue(waiting.cancel(false));
            assertThrows(CancellationException.class,
                    () -> waiting.get(5, TimeUnit.SECONDS));
            assertEquals(before, OresTimers.availableGlobalTimerSlots());
        }
    }
}
