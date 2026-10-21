package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
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
    @Test
    void zeroDelayTimerStillResumesOnLaterSchedulerTurn() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger turns = new AtomicInteger();
            AtomicReference<Long> firstDispatch = new AtomicReference<>();
            OresFuture<Integer> done = scheduler.start(resume -> {
                if (turns.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    return OresScheduler.await(timers.afterNanos(0));
                }
                assertSame(scheduler, OresScheduler.current());
                assertNotEquals(firstDispatch.get().longValue(),
                        OresScheduler.currentDispatchId());
                assertNull(resume.failure());
                return OresScheduler.done(7);
            });
            assertEquals(7, done.get(5, TimeUnit.SECONDS));
            assertEquals(2, turns.get());
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void cancelledTimerNeverExpiresAndCanReleaseItsCapacity() throws Exception {
        try (OresTimers timers = new OresTimers()) {
            OresFuture<Void> pending = timers.afterNanos(TimeUnit.HOURS.toNanos(1));
            AtomicInteger notifications = new AtomicInteger();
            pending.whenCompleteRuntime((value, failure) -> {
                assertInstanceOf(CancellationException.class, failure);
                notifications.incrementAndGet();
            });
            assertTrue(pending.cancel(false));
            assertFalse(pending.cancel(false));
            assertThrows(CancellationException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals(1, notifications.get());
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void ownerAdmissionIsBoundedAndRecoveredAfterCancellation() {
        try (OresTimers timers = new OresTimers()) {
            java.util.ArrayList<OresFuture<Void>> waiting = new java.util.ArrayList<>();
            for (int i = 0; i < 4096; ++i) {
                waiting.add(timers.afterNanos(TimeUnit.HOURS.toNanos(1)));
            }
            assertEquals(4096, timers.activeTimerCount());
            assertThrows(RejectedExecutionException.class,
                    () -> timers.afterNanos(TimeUnit.HOURS.toNanos(1)));
            for (OresFuture<Void> pending : waiting) {
                assertTrue(pending.cancel(false));
            }
            assertEquals(0, timers.activeTimerCount());
            // No retained admissions after the cancelled batch.
            assertTrue(timers.afterNanos(TimeUnit.HOURS.toNanos(1)).cancel(false));
            assertEquals(0, timers.activeTimerCount());
        }
    }

    @Test
    void timerCancelRacingZeroDelayExpirySettlesOnceWithoutLeakingSlots() throws Exception {
        final int startingPermits = OresTimers.availableGlobalTimerSlots();
        try (OresTimers timers = new OresTimers()) {
            for (int i = 0; i < 300; ++i) {
                OresFuture<Void> pending = timers.afterNanos(0);
                CountDownLatch notification = new CountDownLatch(1);
                AtomicInteger outcomes = new AtomicInteger();
                pending.whenCompleteRuntime((value, failure) -> {
                    outcomes.incrementAndGet();
                    notification.countDown();
                });
                Thread canceller = Thread.ofPlatform().start(() -> pending.cancel(false));
                canceller.join();
                assertTrue(notification.await(5, TimeUnit.SECONDS),
                        "completion notification was lost");
                assertTrue(pending.isDone());
                assertEquals(1, outcomes.get(), "raced timer was delivered twice");
            }
            assertEquals(0, timers.activeTimerCount());
            assertEquals(startingPermits, OresTimers.availableGlobalTimerSlots());
        }
    }

    @Test
    void cancellingPendingIntervalReleasesTimerAndRejectsFurtherNext() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresTimers.Interval interval = timers.intervalNanos(
                     TimeUnit.HOURS.toNanos(1))) {
            OresFuture<Long> first = interval.next();
            assertEquals(1, timers.activeTimerCount());
            assertTrue(interval.cancel());
            assertFalse(interval.cancel());
            assertThrows(CancellationException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> interval.next().get(5, TimeUnit.SECONDS));
            assertEquals(0, timers.activeTimerCount());
            assertEquals(0, timers.activeIntervalCount());
        }
    }

    @Test
    void callbackRunsOnceOnOwningScheduler() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger calls = new AtomicInteger();
            OresTimers.TimerHandle timer = timers.setTimerNanos(0, scheduler, () -> {
                assertSame(scheduler, OresScheduler.current());
                assertFalse(Thread.currentThread().getName()
                        .startsWith("ores-monotonic-timer-"));
                calls.incrementAndGet();
            });
            assertNull(timer.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertFalse(timer.cancel());
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (timers.activeCallbackCount() != 0 && System.nanoTime() < limit) {
                Thread.sleep(1);
            }
            assertEquals(0, timers.activeCallbackCount());
        }
    }

    @Test
    void ownerShutdownCancelsUnfiredCallback() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTimers timers = new OresTimers();
            AtomicInteger calls = new AtomicInteger();
            OresTimers.TimerHandle timer = timers.setTimerNanos(
                TimeUnit.HOURS.toNanos(1), scheduler, calls::incrementAndGet);
            timers.close();
            assertThrows(CancellationException.class,
                () -> timer.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, timers.activeCallbackCount());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void expiryMayBeCancelledWhileSchedulerQueueIsBlocked() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch began = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresFuture<Void> blocker = scheduler.startSync(() -> {
                began.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("barrier timed out");
                return null;
            });
            assertTrue(began.await(5, TimeUnit.SECONDS));
            AtomicInteger called = new AtomicInteger();
            OresTimers.TimerHandle timer = timers.setTimerNanos(0, scheduler,
                    called::incrementAndGet);
            try {
                assertTrue(timer.cancel(), "blocked callback could not be cancelled");
                assertFalse(timer.cancel(), "cancel succeeded twice");
            } finally {
                release.countDown();
            }
            assertNull(blocker.get(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> timer.getDone().get(5, TimeUnit.SECONDS));
            assertNull(scheduler.startSync(() -> null).get(5, TimeUnit.SECONDS));
            assertEquals(0, called.get());
        }
    }

    @Test
    void callbackFailureIsObservableThroughGetDone() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            OresTimers.TimerHandle timer = timers.setTimerNanos(0, scheduler, () -> {
                throw new IllegalStateException("timer callback failure");
            });
            java.util.concurrent.ExecutionException failure =
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> timer.getDone().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("timer callback failure", failure.getCause().getMessage());
            assertFalse(timer.cancel());
        }
    }

    @Test
    void genericSchedulerCallbackCannotBypassActorMailbox() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1);
             ActorRuntime actors = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            var actor = actors.<String>spawnShared(() -> (message, context) -> {
                try {
                    timers.setTimerNanos(0, scheduler, () -> { });
                } catch (Throwable failure) {
                    rejected.set(failure);
                } finally {
                    delivered.countDown();
                    context.self().stop();
                }
            });
            actor.send("attempt");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejected.get());
            assertEquals(0, timers.activeCallbackCount());
        }
    }

    @Test
    void concurrentCancelOrCallbackExecutionHasExactlyOneOutcome() throws Exception {
        try (OresTimers timers = new OresTimers();
             OresScheduler scheduler = new OresScheduler(1)) {
            for (int i = 0; i < 200; ++i) {
                AtomicInteger invoked = new AtomicInteger();
                OresTimers.TimerHandle timer = timers.setTimerNanos(0, scheduler,
                        invoked::incrementAndGet);
                AtomicReference<Boolean> cancelled = new AtomicReference<>();
                Thread competitor = Thread.ofPlatform().start(
                        () -> cancelled.set(timer.cancel()));
                competitor.join();
                try {
                    assertNull(timer.getDone().get(5, TimeUnit.SECONDS));
                    assertFalse(cancelled.get(), "cancel won but callback completed");
                    assertEquals(1, invoked.get());
                } catch (CancellationException expected) {
                    assertTrue(cancelled.get(), "cancelled result without winner");
                    assertEquals(0, invoked.get());
                }
                assertTrue(timer.isDone());
            }
        }
    }

}
