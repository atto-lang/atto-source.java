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
            // Quiescence intentionally precedes terminal Future publication.
            // Waiting for the publisher is necessary; isDone() immediately
            // after the retirement latch is a race, not a VM invariant.
            assertEquals(42, winner.get(3, TimeUnit.SECONDS));
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
    void terminalSubscriberCannotDelayQuiescentResourceRetirement()
            throws Exception {
        // Future subscribers run synchronously on the publishing carrier.
        // Resource/quota retirement must already be complete by the time a
        // subscriber can block that carrier.
        java.util.concurrent.ExecutorService carrier =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "blocked-terminal-subscriber", 1, carrier);
        OresFuture<Integer> releaseTask = new OresFuture<>();
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger retirements = new AtomicInteger();
        try {
            OresFuture<Integer> result = scheduler.start(resume -> {
                if (resume.initial()) {
                    stepEntered.countDown();
                    return OresScheduler.await(releaseTask);
                }
                return OresScheduler.done(42);
            }, () -> {
                retirements.incrementAndGet();
                retired.countDown();
            });
            assertTrue(stepEntered.await(3, TimeUnit.SECONDS));
            result.whenCompleteRuntime((value, failure) -> {
                subscriberEntered.countDown();
                try {
                    releaseSubscriber.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            releaseTask.completeFromRuntime(1);
            assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
            assertTrue(retired.await(2, TimeUnit.SECONDS),
                    "terminal subscribers must not retain quiescent task resources");
            assertEquals(1, retirements.get());
            scheduler.close();
            assertEquals(1, retirements.get());
        } finally {
            releaseSubscriber.countDown();
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(3, TimeUnit.SECONDS));
        }
    }


    @Test
    void blockedCloseSubscriberCannotDelayOtherTasksInternalRevocation()
            throws Exception {
        // Cancel() invokes runtime Future subscribers synchronously. If one
        // subscriber blocks inside close(), every task in the close snapshot
        // must nevertheless have detached its await and retired its idle
        // internal resources before the first subscriber was notified.
        java.util.concurrent.ExecutorService carrier =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "blocked-close-multiple-tasks", 1, carrier);
        OresFuture<Integer> neverCompletes = new OresFuture<>();
        java.util.List<OresFuture<Integer>> results = new java.util.ArrayList<>();
        CountDownLatch retired = new CountDownLatch(3);
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        AtomicInteger retireCount = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> closeFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread closer = null;
        try {
            for (int i = 0; i < 3; i++) {
                OresFuture<Integer> result = scheduler.start(
                        resume -> OresScheduler.await(neverCompletes),
                        () -> {
                            retireCount.incrementAndGet();
                            retired.countDown();
                        });
                results.add(result);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (neverCompletes.pendingRuntimeWaiterCount() != 3
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(3, neverCompletes.pendingRuntimeWaiterCount(),
                    "all three tasks must suspend before shutdown");
            for (OresFuture<Integer> result : results) {
                result.whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    try {
                        releaseSubscriber.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            closer = Thread.ofPlatform().start(() -> {
                try {
                    scheduler.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS),
                    "a close subscriber must be blocked before inspecting other tasks");
            assertTrue(retired.await(2, TimeUnit.SECONDS),
                    "all tasks must retire before delivering any terminal subscriber");
            assertEquals(3, retireCount.get());
            assertEquals(0, neverCompletes.pendingRuntimeWaiterCount(),
                    "all suspended await registrations must be detached");
        } finally {
            releaseSubscriber.countDown();
            if (closer != null) {
                closer.join(3_000);
                assertFalse(closer.isAlive(), "close failed to finish after subscriber release");
            }
            scheduler.close();
            carrier.shutdownNow();
            assertTrue(carrier.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(null, closeFailure.get());
        for (OresFuture<Integer> result : results) {
            assertTrue(result.isCancelled(), "shutdown must publish cancellation");
        }
    }


    @Test
    void terminalCallbackMayCloseOwningSchedulerWithoutWaitingOnItsCarrier()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        CountDownLatch insideTurn = new CountDownLatch(1);
        CountDownLatch finishTurn = new CountDownLatch(1);
        CountDownLatch callbackExited = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            OresFuture<Integer> future = scheduler.start(resume -> {
                insideTurn.countDown();
                assertTrue(finishTurn.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(17);
            });
            assertTrue(insideTurn.await(3, TimeUnit.SECONDS));
            future.whenCompleteRuntime((value, failure) -> {
                try {
                    assertEquals(17, value);
                    assertEquals(null, failure);
                    // This callback runs after runBound has cleared CURRENT,
                    // but before afterCarrierTurn finishes on the carrier.
                    scheduler.close();
                } catch (Throwable thrown) {
                    callbackFailure.set(thrown);
                } finally {
                    callbackExited.countDown();
                }
            });
            finishTurn.countDown();
            assertTrue(callbackExited.await(2, TimeUnit.SECONDS),
                    "scheduler close must not await its own completion carrier");
            assertEquals(null, callbackFailure.get());
            assertTrue(scheduler.isClosed());
            assertEquals(17, future.get(3, TimeUnit.SECONDS));
        } finally {
            finishTurn.countDown();
            scheduler.close();
        }
    }


    @Test
    void wallDeadlineFailsNeverSettlingAwaitWithoutAnotherCarrierTurn()
            throws Exception {
        OresFuture<Integer> never = new OresFuture<>();
        CountDownLatch retired = new CountDownLatch(1);
        AtomicInteger guestTurns = new AtomicInteger();
        IllegalStateException deadlineFailure = new IllegalStateException("logical wall deadline");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.startWithWallDeadline(resume -> {
                guestTurns.incrementAndGet();
                return OresScheduler.await(never);
            }, retired::countDown, TimeUnit.MILLISECONDS.toNanos(160), deadlineFailure);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (never.pendingRuntimeWaiterCount() != 1 && System.nanoTime() < until) {
                Thread.sleep(1);
            }
            assertEquals(1, never.pendingRuntimeWaiterCount(),
                    "await must be registered before the logical timeout");
            java.util.concurrent.ExecutionException failed =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failed.getCause() == deadlineFailure,
                    "deadline must retain its typed runtime failure");
            assertTrue(retired.await(2, TimeUnit.SECONDS));
            assertEquals(0, never.pendingRuntimeWaiterCount(),
                    "an expired task cannot remain retained by an unresolved Future");
            assertEquals(1, guestTurns.get(),
                    "the timeout must not enter an additional guest carrier turn");
        }
    }

    @Test
    void wallDeadlineCannotReleaseGuestResourcesWhileCarrierStillExecuting()
            throws Exception {
        CountDownLatch insideGuest = new CountDownLatch(1);
        CountDownLatch releaseGuest = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        IllegalStateException deadlineFailure = new IllegalStateException("elapsed");
        AtomicInteger retirements = new AtomicInteger();
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Integer> result = scheduler.startWithWallDeadline(resume -> {
                insideGuest.countDown();
                assertTrue(releaseGuest.await(3, TimeUnit.SECONDS));
                return OresScheduler.done(99);
            }, () -> {
                retirements.incrementAndGet();
                retired.countDown();
            }, TimeUnit.MILLISECONDS.toNanos(70), deadlineFailure);
            assertTrue(insideGuest.await(2, TimeUnit.SECONDS));
            Thread.sleep(140);
            assertFalse(result.isDone(),
                    "terminal publication must wait for the guest turn to unwind");
            assertEquals(0, retirements.get(),
                    "deadline must not reclaim memory beneath the live guest");
            releaseGuest.countDown();
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            java.util.concurrent.ExecutionException failed =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failed.getCause() == deadlineFailure);
            assertEquals(1, retirements.get(),
                    "physical quiescence must release owned resources exactly once");
        } finally {
            releaseGuest.countDown();
            scheduler.close();
        }
    }


    @Test
    void blockingDeadlineSubscribersCannotStarveAnotherActorsDeadline()
            throws Exception {
        // Two host completion subscribers intentionally block forever until
        // released. They must not occupy both independent timer workers and
        // prevent a third suspended task's deadline from being enforced.
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> never = new OresFuture<>();
        CountDownLatch subscribersEntered = new CountDownLatch(2);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(3);
        java.util.List<OresFuture<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                OresFuture<Integer> future = scheduler.startWithWallDeadline(
                        resume -> OresScheduler.await(never),
                        retired::countDown,
                        TimeUnit.MILLISECONDS.toNanos(180),
                        new IllegalStateException("wall-" + i));
                futures.add(future);
                if (i < 2) {
                    future.whenCompleteRuntime((value, failure) -> {
                        subscribersEntered.countDown();
                        try {
                            unblock.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
            assertTrue(subscribersEntered.await(3, TimeUnit.SECONDS),
                    "both terminal subscribers must be blocked");
            java.util.concurrent.ExecutionException expired =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> futures.get(2).get(2, TimeUnit.SECONDS));
            assertEquals("wall-2", expired.getCause().getMessage());
            assertTrue(retired.await(2, TimeUnit.SECONDS),
                    "deadline scanner must retire all three idle tasks independently");
            assertEquals(0, never.pendingRuntimeWaiterCount());
        } finally {
            unblock.countDown();
            scheduler.close();
        }
    }


    @Test
    void globalTimedAdmissionStaysBoundedThroughBlockedFutureNotifications()
            throws Exception {
        int before = OresScheduler.availableWallDeadlineAdmissions();
        assertTrue(before >= 2, "expected admission capacity for timed tasks");
        CountDownLatch notificationEntered = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        java.util.List<OresFuture<Integer>> admitted = new java.util.ArrayList<>();
        OresFuture<Integer> unresolved = new OresFuture<>();
        OresScheduler scheduler = new OresScheduler(1);
        Thread cancelling = null;
        try {
            for (int i = 0; i < before; i++) {
                admitted.add(scheduler.startWithWallDeadline(
                        resume -> OresScheduler.await(unresolved),
                        () -> { },
                        TimeUnit.SECONDS.toNanos(30),
                        new IllegalStateException("quota " + i)));
            }
            assertEquals(0, OresScheduler.availableWallDeadlineAdmissions(),
                    "global timed admission must have a fixed upper bound");
            OresFuture<Integer> first = admitted.get(0);
            first.whenCompleteRuntime((value, failure) -> {
                notificationEntered.countDown();
                try {
                    releaseNotification.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            cancelling = Thread.ofVirtual().start(() -> first.cancel(false));
            assertTrue(notificationEntered.await(2, TimeUnit.SECONDS));
            assertTrue(first.isDone(),
                    "terminal state may be visible while a subscriber is blocked");
            assertEquals(0, OresScheduler.availableWallDeadlineAdmissions(),
                    "settlement must not reuse a slot until subscribers drain");
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> scheduler.startWithWallDeadline(
                            resume -> OresScheduler.done(5),
                            () -> { },
                            TimeUnit.SECONDS.toNanos(30),
                            new IllegalStateException("overload")));
            releaseNotification.countDown();
            cancelling.join(3_000);
            assertFalse(cancelling.isAlive());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (OresScheduler.availableWallDeadlineAdmissions() == 0
                    && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(OresScheduler.availableWallDeadlineAdmissions() >= 1,
                    "a drained cancellation must restore a global slot");
            OresFuture<Integer> successor = scheduler.startWithWallDeadline(
                    resume -> OresScheduler.done(7),
                    () -> { },
                    TimeUnit.SECONDS.toNanos(30),
                    new IllegalStateException("unexpected expiration"));
            assertEquals(7, successor.get(3, TimeUnit.SECONDS));
        } finally {
            releaseNotification.countDown();
            if (cancelling != null) cancelling.join(3_000);
            for (OresFuture<Integer> future : admitted) future.cancel(false);
            scheduler.close();
        }
        assertEquals(before, OresScheduler.availableWallDeadlineAdmissions(),
                "shutdown must release all timed admission reservations");
    }

    @Test
    void wallDeadlineCompletionCancellationAndShutdownRaceRetiresExactlyOnce()
            throws Exception {
        int before = OresScheduler.availableWallDeadlineAdmissions();
        int count = 30;
        CountDownLatch retired = new CountDownLatch(count);
        java.util.concurrent.atomic.AtomicIntegerArray retireCounts =
                new java.util.concurrent.atomic.AtomicIntegerArray(count);
        java.util.List<OresFuture<Integer>> results = new java.util.ArrayList<>();
        java.util.List<OresFuture<Integer>> producers = new java.util.ArrayList<>();
        java.util.List<Thread> competitors = new java.util.ArrayList<>();
        OresScheduler scheduler = new OresScheduler(4);
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                OresFuture<Integer> producer = new OresFuture<>();
                producers.add(producer);
                results.add(scheduler.startWithWallDeadline(
                        resume -> resume.initial()
                                ? OresScheduler.<Integer>await(producer)
                                : OresScheduler.done(17),
                        () -> {
                            retireCounts.incrementAndGet(index);
                            retired.countDown();
                        },
                        TimeUnit.MILLISECONDS.toNanos(150),
                        new IllegalStateException("expired " + index)));
            }
            for (int i = 0; i < count; i++) {
                final int index = i;
                if (i % 3 == 0) {
                    competitors.add(Thread.ofVirtual().start(() ->
                            results.get(index).cancel(false)));
                } else if (i % 3 == 1) {
                    competitors.add(Thread.ofVirtual().start(() ->
                            producers.get(index).completeFromRuntime(17)));
                }
            }
            for (Thread thread : competitors) thread.join(3_000);
            for (OresFuture<Integer> future : results) {
                try {
                    future.get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException
                        | java.util.concurrent.CancellationException expected) {
                    // Success, cancellation and timeout may win the race.
                }
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < count; i++) {
                assertEquals(1, retireCounts.get(i),
                        "exactly-once physical retirement of task " + i);
                assertEquals(0, producers.get(i).pendingRuntimeWaiterCount(),
                        "terminal task " + i + " must detach its producer await");
            }
        } finally {
            scheduler.close();
        }
        assertEquals(before, OresScheduler.availableWallDeadlineAdmissions(),
                "all deadline reservations must be released across races");
    }


    @Test
    void blockedQuiescenceCallbacksCannotOccupyDeadlineTimerWorkers()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresFuture<Integer> unresolved = new OresFuture<>();
        CountDownLatch twoRetirementsEntered = new CountDownLatch(2);
        CountDownLatch releaseRetirements = new CountDownLatch(1);
        CountDownLatch thirdRetired = new CountDownLatch(1);
        try {
            java.util.List<OresFuture<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int index = i;
                results.add(scheduler.startWithWallDeadline(
                        resume -> OresScheduler.await(unresolved),
                        () -> {
                            if (index == 2) {
                                thirdRetired.countDown();
                            } else {
                                twoRetirementsEntered.countDown();
                                try {
                                    releaseRetirements.await(3, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        },
                        TimeUnit.MILLISECONDS.toNanos(130),
                        new IllegalStateException("expired-" + i)));
            }
            assertTrue(twoRetirementsEntered.await(3, TimeUnit.SECONDS));
            assertTrue(thirdRetired.await(2, TimeUnit.SECONDS),
                    "deadline worker must not wait for blocked actor retirement");
            java.util.concurrent.ExecutionException expired =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> results.get(2).get(2, TimeUnit.SECONDS));
            assertEquals("expired-2", expired.getCause().getMessage());
        } finally {
            releaseRetirements.countDown();
            scheduler.close();
        }
    }

    @Test
    void directCancellationRevokesAwaitBeforeBlockedSubscriberDelivery()
            throws Exception {
        OresFuture<Integer> neverSettles = new OresFuture<>();
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        CountDownLatch physicallyRetired = new CountDownLatch(1);
        AtomicInteger retirements = new AtomicInteger();
        int before = OresScheduler.availableWallDeadlineAdmissions();
        OresScheduler scheduler = new OresScheduler(1);
        Thread canceller = null;
        try {
            OresFuture<Integer> result = scheduler.startWithWallDeadline(
                    resume -> OresScheduler.await(neverSettles),
                    () -> {
                        retirements.incrementAndGet();
                        physicallyRetired.countDown();
                    },
                    TimeUnit.SECONDS.toNanos(30),
                    new IllegalStateException("unexpected expiration"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (neverSettles.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, neverSettles.pendingRuntimeWaiterCount());

            result.whenCompleteRuntime((value, failure) -> {
                subscriberEntered.countDown();
                try {
                    releaseSubscriber.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            canceller = Thread.ofPlatform().start(() -> result.cancel(false));
            assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS),
                    "host subscriber must be inside its blocking callback");
            assertTrue(result.isCancelled());
            assertTrue(physicallyRetired.await(2, TimeUnit.SECONDS),
                    "guest task must retire independently of subscriber release");
            assertEquals(0, neverSettles.pendingRuntimeWaiterCount(),
                    "internal cancellation hook must detach an unresolved await");
            assertEquals(1, retirements.get());
            assertEquals(before - 1, OresScheduler.availableWallDeadlineAdmissions(),
                    "blocked subscriber still retains its notification reservation");
        } finally {
            releaseSubscriber.countDown();
            if (canceller != null) {
                canceller.join(3_000);
                assertFalse(canceller.isAlive());
            }
            scheduler.close();
        }
        assertEquals(before, OresScheduler.availableWallDeadlineAdmissions(),
                "terminal callback drain must eventually release the admission");
    }

    @Test
    void cancellingFutureInvokesInternalHookBeforeSynchronousSubscriber()
            throws Exception {
        AtomicBoolean revoked = new AtomicBoolean();
        OresFuture<Integer> result = new OresFuture<>(() -> revoked.set(true));
        AtomicBoolean sawRevocation = new AtomicBoolean();
        result.whenCompleteRuntime((value, failure) -> {
            sawRevocation.set(revoked.get());
        });
        assertTrue(result.cancel(false));
        assertTrue(sawRevocation.get(),
                "pre-registered Future subscriber must observe internal revocation");
    }

    @Test
    void lateCancelSubscriberCannotBypassInProgressInternalRevocation()
            throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(() -> {
            hookEntered.countDown();
            try {
                assertTrue(releaseHook.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        Thread cancelling = Thread.ofPlatform().start(() -> future.cancel(false));
        try {
            assertTrue(hookEntered.await(3, TimeUnit.SECONDS));
            assertTrue(future.isCancelled(),
                    "terminal cancellation is visible while revoker executes");
            // Must neither invoke the callback early nor synchronously block
            // its registering thread on a live cancellation hook.
            future.whenCompleteRuntime((value, failure) -> {
                callbacks.incrementAndGet();
                notified.countDown();
            });
            assertEquals(0, callbacks.get(),
                    "late waiter must not bypass the internal cancellation hook");
        } finally {
            releaseHook.countDown();
            cancelling.join(3_000);
            assertFalse(cancelling.isAlive(),
                    "cancelling thread must complete its waiter drain");
        }
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(1, callbacks.get(),
                "cancel hook race must never duplicate terminal notification");
    }

    @Test
    void cancellationHookCanRegisterAnotherCallbackWithoutSelfDeadlock()
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger callbackCount = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(() ->
                holder.get().whenCompleteRuntime((value, failure) ->
                        callbackCount.incrementAndGet()));
        holder.set(future);
        assertTrue(future.cancel(false));
        assertEquals(1, callbackCount.get(),
                "hook-installed callback must drain exactly once after revocation");
    }

    @Test
    void timedAdmissionIsGloballyBoundedAcrossIndependentSchedulers()
            throws Exception {
        int original = OresScheduler.availableWallDeadlineAdmissions();
        assertTrue(original >= 32);
        java.util.List<OresScheduler> schedulers = new java.util.ArrayList<>();
        OresFuture<Integer> unresolved = new OresFuture<>();
        java.util.List<OresFuture<Integer>> admitted = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) schedulers.add(new OresScheduler(1));
            for (int i = 0; i < original; i++) {
                final int index = i;
                admitted.add(schedulers.get(i % schedulers.size())
                        .startWithWallDeadline(
                                resume -> OresScheduler.await(unresolved),
                                () -> { },
                                TimeUnit.SECONDS.toNanos(30),
                                new IllegalStateException("unexpected deadline " + index)));
            }
            assertEquals(0, OresScheduler.availableWallDeadlineAdmissions(),
                    "the timed quota must be process-wide, not per scheduler");
            for (OresScheduler scheduler : schedulers) {
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        () -> scheduler.startWithWallDeadline(
                                resume -> OresScheduler.done(55),
                                () -> { },
                                TimeUnit.SECONDS.toNanos(30),
                                new IllegalStateException("over capacity")));
            }
            // Shutdown crosses multiple physical executors but must retire
            // every reservation, including QUEUED and WAITING tasks.
            for (OresScheduler scheduler : schedulers) scheduler.close();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (OresScheduler.availableWallDeadlineAdmissions() != original
                    && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(original, OresScheduler.availableWallDeadlineAdmissions(),
                    "all independent scheduler shutdowns must reclaim their slots");
            assertEquals(0, unresolved.pendingRuntimeWaiterCount(),
                    "cross-scheduler close must detach all unresolved awaits");
            for (OresFuture<Integer> task : admitted) {
                assertTrue(task.isDone(), "shutdown cannot strand an admitted task");
            }
        } finally {
            for (OresScheduler scheduler : schedulers) scheduler.close();
        }
        assertEquals(original, OresScheduler.availableWallDeadlineAdmissions());
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
