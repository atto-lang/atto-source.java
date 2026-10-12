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
                java.lang.reflect.Field tasksField =
                        OresScheduler.class.getDeclaredField("tasks");
                tasksField.setAccessible(true);
                java.util.Set<?> liveTasks =
                        (java.util.Set<?>) tasksField.get(scheduler);
                assertEquals(1, liveTasks.size(),
                        "cancelled task must remain registered until carrier unwinds");
            } finally {
                exitGuest.countDown();
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, retires.get());
            java.lang.reflect.Field tasksField =
                    OresScheduler.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            assertTrue(((java.util.Set<?>) tasksField.get(scheduler)).isEmpty(),
                    "quiescent task must be removed from scheduler live set");
        }
    }

    @Test
    void runtimeOwnedSchedulerCloseCannotEraseStillRunningTask()
            throws Exception {
        java.util.concurrent.ExecutorService carrier =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "test-close-quiescence", 1, carrier);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            OresFuture<Integer> result = scheduler.start(resume -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            }, () -> {
                callbacks.incrementAndGet();
                retired.countDown();
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            scheduler.close();
            assertTrue(result.isCancelled(),
                    "scheduler shutdown cancels the public Future");
            assertEquals(0, callbacks.get(),
                    "shutdown cannot reclaim resources on a live guest carrier");
            java.lang.reflect.Field tasksField =
                    OresScheduler.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            assertEquals(1, ((java.util.Set<?>) tasksField.get(scheduler)).size(),
                    "close must retain in-flight task until carrier unwinds");
        } finally {
            release.countDown();
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            java.lang.reflect.Field tasksField =
                    OresScheduler.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            assertTrue(((java.util.Set<?>) tasksField.get(scheduler)).isEmpty());
            carrier.shutdownNow();
            carrier.awaitTermination(3, TimeUnit.SECONDS);
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
    void schedulerCloseCannotMissTaskWhoseAdmissionIsInProgress()
            throws Exception {
        CountDownLatch admissionEntered = new CountDownLatch(1);
        CountDownLatch finishAdmission = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger guestExecutions = new AtomicInteger();
        AtomicInteger retireCount = new AtomicInteger();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "race-close-admission", 1, runnable -> {
                    // Deliberately pause the executor admission without
                    // scheduling work. close must include this registered task.
                    admissionEntered.countDown();
                    try {
                        if (!finishAdmission.await(3, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out holding admission");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.RejectedExecutionException(interrupted);
                    }
                });
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                result.set(scheduler.start(resume -> {
                    guestExecutions.incrementAndGet();
                    return OresScheduler.done(5);
                }, retireCount::incrementAndGet));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        Thread closer = null;
        try {
            assertTrue(admissionEntered.await(3, TimeUnit.SECONDS));
            closer = Thread.ofPlatform().start(() -> {
                closeStarted.countDown();
                try {
                    scheduler.close();
                } finally {
                    closeFinished.countDown();
                }
            });
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS));
            assertTrue(closeFinished.await(2, TimeUnit.SECONDS),
                    "close must not wait for an arbitrary blocked executor enqueue");
            assertEquals(1, retireCount.get(),
                    "close retires a registered task before blocked enqueue returns");
        } finally {
            finishAdmission.countDown();
            starter.join(3_000);
            if (closer != null) closer.join(3_000);
            scheduler.close();
        }
        assertFalse(starter.isAlive());
        if (closer != null) assertFalse(closer.isAlive());
        assertEquals(null, error.get());
        assertTrue(result.get() != null, "admitted task must be observable");
        assertTrue(result.get().isCancelled(),
                "shutdown must cancel the task admitted before its close fence");
        assertEquals(0, guestExecutions.get());
        assertEquals(1, retireCount.get());
        java.lang.reflect.Field tasksField =
                OresScheduler.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        assertTrue(((java.util.Set<?>) tasksField.get(scheduler)).isEmpty());
    }


    @Test
    void shutdownFinishesTaskCancellationWhenFutureWaiterStallsItsHook()
            throws Exception {
        // Future.cancel publishes its terminal state before invoking the task
        // cancellation hook. A synchronous completion waiter can block between
        // those two events, so close must not rely only on cancel(false).
        java.util.concurrent.ExecutorService carrier =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "stalled-cancel-hook", 1, carrier);
        OresFuture<Integer> unresolved = new OresFuture<>();
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retirementCount = new AtomicInteger();
        Thread canceller = null;
        try {
            OresFuture<Integer> result = scheduler.start(resume -> {
                stepEntered.countDown();
                return OresScheduler.await(unresolved);
            }, () -> {
                retirementCount.incrementAndGet();
                retired.countDown();
            });
            assertTrue(stepEntered.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (unresolved.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, unresolved.pendingRuntimeWaiterCount(),
                    "task must be suspended before starting the cancellation race");
            result.whenCompleteRuntime((value, failure) -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            canceller = Thread.ofPlatform().start(() -> result.cancel(false));
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS),
                    "Future has settled but its cancellation hook cannot run yet");
            assertTrue(result.isCancelled());

            scheduler.close();
            assertTrue(retired.await(2, TimeUnit.SECONDS),
                    "close must retire a suspended task even if cancel's hook is blocked");
            assertEquals(1, retirementCount.get());
            assertEquals(0, unresolved.pendingRuntimeWaiterCount(),
                    "close must detach the suspended task's awaited Future");
        } finally {
            releaseCallback.countDown();
            if (canceller != null) {
                canceller.join(3_000);
                assertFalse(canceller.isAlive(), "cancellation callback was stranded");
            }
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(3, TimeUnit.SECONDS));
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
