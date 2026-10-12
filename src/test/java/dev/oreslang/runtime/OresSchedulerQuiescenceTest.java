package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A Future cancellation is NOT proof that a physical guest carrier has exited. */
@Timeout(15)
final class OresSchedulerQuiescenceTest {
    @Test
    void cancellationDoesNotRetireRunningTaskUntilCarrierUnwinds() throws Exception {
        CountDownLatch insideGuest = new CountDownLatch(1);
        CountDownLatch exitGuest = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retires = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(resume -> {
                insideGuest.countDown();
                assertTrue(exitGuest.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            }, () -> {
                retires.incrementAndGet();
                retired.countDown();
            });
            try {
                assertTrue(insideGuest.await(3, TimeUnit.SECONDS));
                assertTrue(result.cancel(false));
                assertTrue(result.isCancelled(), "Future is already settled");
                assertEquals(0, retires.get(),
                        "guest resources cannot be retired while a carrier remains live");
            } finally {
                exitGuest.countDown();
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, retires.get());
        }
    }

    @Test
    void cancellationBeforeGuestAdmissionSkipsGuestAndRetiresOnlyOnce()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstReleased = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retireCount = new AtomicInteger();
        AtomicBoolean secondGuestEntered = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> first = scheduler.start(resume -> {
                firstEntered.countDown();
                assertTrue(firstReleased.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(7);
            });
            try {
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
                OresFuture<Integer> second = scheduler.start(resume -> {
                    secondGuestEntered.set(true);
                    return OresScheduler.done(99);
                }, () -> {
                    retireCount.incrementAndGet();
                    retired.countDown();
                });
                assertTrue(second.cancel(false));
                assertTrue(retired.await(3, TimeUnit.SECONDS));
                assertEquals(1, retireCount.get());
            } finally {
                firstReleased.countDown();
            }
            assertEquals(7, first.get(3, TimeUnit.SECONDS));
            assertFalse(secondGuestEntered.get(),
                    "cancelled queued turn must not run guest code later");
            assertEquals(1, retireCount.get());
        }
    }

    @Test
    void losingDuplicateDispatchNeverReleasesWinnersPhysicalLease()
            throws Exception {
        CountDownLatch winnerEntered = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retireCount = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> winner = scheduler.start(resume -> {
                winnerEntered.countDown();
                assertTrue(releaseWinner.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            }, () -> {
                retireCount.incrementAndGet();
                retired.countDown();
            });
            try {
                assertTrue(winnerEntered.await(3, TimeUnit.SECONDS));
                // Inject a duplicate physical dispatch for the same task. This
                // is otherwise unrepresentable via the public scheduling API,
                // but it proves that a losing stale callback cannot release
                // the winner's execution lease or watchdog.
                java.lang.reflect.Field tasksField =
                        OresScheduler.class.getDeclaredField("tasks");
                tasksField.setAccessible(true);
                java.util.Set<?> tasks = (java.util.Set<?>) tasksField.get(scheduler);
                Object taskRunner = tasks.iterator().next();
                java.lang.reflect.Method enqueue =
                        taskRunner.getClass().getDeclaredMethod("enqueueTurn");
                enqueue.setAccessible(true);
                enqueue.invoke(taskRunner);

                // Same second carrier executes a FIFO barrier after the
                // duplicate, proving the losing callback already ran.
                OresFuture<Integer> barrier = scheduler.start(
                        resume -> OresScheduler.done(7));
                assertEquals(7, barrier.get(3, TimeUnit.SECONDS));
                assertEquals(0, retireCount.get(),
                        "duplicate cleanup must not release a live winner");
                assertFalse(retired.await(50, TimeUnit.MILLISECONDS),
                        "quota retirement cannot precede winner's unwinding");
            } finally {
                releaseWinner.countDown();
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, retireCount.get());
            assertTrue(winner.isDone());
        }
    }

    @Test
    void repeatedCancelCooperateRacesRetireExactlyOncePerTask() throws Exception {
        final int iterations = 128;
        AtomicInteger retirements = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(3)) {
            for (int i = 0; i < iterations; i++) {
                CountDownLatch retired = new CountDownLatch(1);
                AtomicInteger perTask = new AtomicInteger();
                OresFuture<Integer> future = scheduler.start(resume -> {
                    // Legitimate handoffs must unwind rather than recurse;
                    // concurrent cancellation may settle at any phase.
                    Thread.onSpinWait();
                    return OresScheduler.cooperate();
                }, () -> {
                    perTask.incrementAndGet();
                    retirements.incrementAndGet();
                    retired.countDown();
                });
                future.cancel(false);
                assertTrue(retired.await(3, TimeUnit.SECONDS),
                        "quiescence callback was stranded in cancellation race " + i);
                assertEquals(1, perTask.get(),
                        "each task must retire exactly once");
            }
            assertEquals(iterations, retirements.get());
        }
    }

    @Test
    void successfulTurnRetiresExactlyOnceAfterCompletion() throws Exception {
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(
                    resume -> OresScheduler.done(13),
                    () -> {
                        count.incrementAndGet();
                        retired.countDown();
                    });
            assertEquals(13, future.get(3, TimeUnit.SECONDS));
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        }
    }
}
