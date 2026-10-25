package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Parent cancellation and join barriers for explicit host Task scopes. */
public class TaskScopeTest {
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test void sealingEmptyScopeCompletesWithoutTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            assertFalse(scope.getDone().isDone());
            scope.seal();
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(scope.getDone().cancel(true));
        }
    }

    @Test void sealedScopeJoinsSuccessesWithoutCancelling() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            TaskHandle<Integer> a = scope.startTask(r -> OresScheduler.done(2));
            TaskHandle<Integer> b = scope.startTask(r -> OresScheduler.done(3));
            scope.seal();
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(2), a.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(3), b.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(scope.isCancellationRequested());
        }
    }

    @Test void guestTurnCanCancelItsOwnScopeAndFinishFinally() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            AtomicBoolean cleanupRan = new AtomicBoolean();
            AtomicBoolean cancelledInsideTurn = new AtomicBoolean();
            TaskHandle<Integer> child = scope.startTask(resume -> {
                try {
                    cancelledInsideTurn.set(scope.cancel("self-stop"));
                    // Scope cancellation requests are cooperative: the
                    // current compiler-generated turn may finish this block.
                    return OresScheduler.done(9);
                } finally {
                    cleanupRan.set(true);
                }
            });
            assertInstanceOf(TaskHandle.Cancelled.class,
                    child.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(child.result().isCancelled(),
                    "inline scheduler may finish before TaskHandle Future binding");
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(cancelledInsideTurn.get());
            assertTrue(cleanupRan.get());
        }
    }

    @Test void scopeCancelCannotPretendRunningChildExited() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> a = scope.startTask(r -> {
                bothStarted.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(10);
            });
            TaskHandle<Integer> b = scope.startTask(r -> {
                bothStarted.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(20);
            });
            assertTrue(bothStarted.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(scope.cancel("shutdown"));
                assertTrue(a.isCancellationRequested());
                assertTrue(b.isCancellationRequested());
                assertFalse(a.result().isDone(),
                        "running child cannot be revoked until its checkpoint");
                assertFalse(b.result().isDone(),
                        "running child cannot be revoked until its checkpoint");
                assertFalse(scope.getDone().isDone());
                assertThrows(TimeoutException.class, () ->
                        scope.getDone().get(30, TimeUnit.MILLISECONDS));
                assertFalse(scope.getDone().cancel(false));
            } finally {
                release.countDown();
            }
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    a.getDone().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    b.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellingChildLeavesSiblingAndScopeLive() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> a = scope.startTask(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(9);
            });
            TaskHandle<Integer> b = scope.startTask(r -> OresScheduler.done(16));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(a.cancel("only-a"));
                assertEquals(new TaskHandle.Success<>(16),
                        b.getDone().get(3, TimeUnit.SECONDS));
                assertFalse(scope.isCancellationRequested());
            } finally {
                release.countDown();
            }
            scope.seal();
            scope.getDone().get(3, TimeUnit.SECONDS);
            assertInstanceOf(TaskHandle.Cancelled.class,
                    a.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cannotAdmitChildAfterSealOrCancel() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope sealed = scheduler.newTaskScope();
            sealed.seal();
            assertThrows(RejectedExecutionException.class,
                    () -> sealed.startTask(r -> OresScheduler.done(1)));
            TaskScope cancelled = scheduler.newTaskScope();
            cancelled.cancel("stop");
            assertThrows(RejectedExecutionException.class,
                    () -> cancelled.startTask(r -> OresScheduler.done(1)));
        }
    }

    @Test void invalidReasonDoesNotSealOrCancelScope() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            assertThrows(IllegalArgumentException.class, () -> scope.cancel(" "));
            assertThrows(IllegalArgumentException.class, () -> scope.cancel("x".repeat(257)));
            assertFalse(scope.isCancellationRequested());
            TaskHandle<Integer> h = scope.startTask(r -> OresScheduler.done(6));
            scope.seal();
            assertEquals(new TaskHandle.Success<>(6),
                    h.getDone().get(3, TimeUnit.SECONDS));
            scope.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test void completedChildrenDoNotReleaseScopeUntilSealed() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            TaskHandle<Integer> h = scope.startTask(r -> OresScheduler.done(1));
            h.getDone().get(3, TimeUnit.SECONDS);
            assertFalse(scope.getDone().isDone());
            scope.seal();
            assertTrue(scope.getDone().isDone());
        }
    }

    @Test void concurrentSealVersusAdmissionDoesNotOrphanAcceptedWork() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int i = 0; i < 100; i++) {
                TaskScope scope = scheduler.newTaskScope();
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger accepted = new AtomicInteger();
                AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
                Thread starter = new Thread(() -> {
                    awaitUninterruptibly(go);
                    try {
                        TaskHandle<Integer> h = scope.startTask(r -> OresScheduler.done(1));
                        accepted.incrementAndGet();
                        assertEquals(new TaskHandle.Success<>(1),
                                h.getDone().get(3, TimeUnit.SECONDS));
                    } catch (RejectedExecutionException ignored) {
                        // Seal won admission race.
                    } catch (Throwable error) {
                        backgroundFailure.compareAndSet(null, error);
                    }
                });
                Thread sealer = new Thread(() -> {
                    awaitUninterruptibly(go);
                    scope.seal();
                });
                starter.start();
                sealer.start();
                go.countDown();
                starter.join(3000);
                sealer.join(3000);
                assertFalse(starter.isAlive());
                assertFalse(sealer.isAlive());
                if (backgroundFailure.get() != null) {
                    throw new AssertionError("background admission failed",
                            backgroundFailure.get());
                }
                scope.getDone().get(3, TimeUnit.SECONDS);
                assertTrue(accepted.get() == 0 || accepted.get() == 1);
            }
        }
    }

    @Test void successfulTasksDoNotAccumulateOwnedCancellationNodes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            for (int i = 0; i < 500; i++) {
                TaskHandle<Integer> child = scope.startTask(r -> OresScheduler.done(1));
                assertEquals(new TaskHandle.Success<>(1),
                        child.getDone().get(3, TimeUnit.SECONDS));
                assertEquals(0, scope.liveCancellationChildren(),
                        "completed Tasks must not remain owned until scope shutdown");
            }
            assertFalse(scope.isCancellationRequested());
            scope.seal();
            scope.getDone().get(3, TimeUnit.SECONDS);
            assertEquals(0, scope.liveCancellationChildren());
        }
    }

    @Test void completedChildKeepsSuccessWhenScopeCancelledLater() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            TaskHandle<Integer> child = scope.startTask(r -> OresScheduler.done(7));
            assertEquals(new TaskHandle.Success<>(7),
                    child.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, scope.liveCancellationChildren());
            assertTrue(scope.cancel("late shutdown"));
            scope.getDone().get(3, TimeUnit.SECONDS);
            assertFalse(child.isCancellationRequested());
            assertFalse(child.cancel("late"));
            assertEquals(new TaskHandle.Success<>(7),
                    child.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationVersusPhysicalExitDoesNotRetainScopeChildren() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            for (int i = 0; i < 200; i++) {
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                TaskHandle<Integer> child = scope.startTask(resume -> {
                    started.countDown();
                    awaitUninterruptibly(release);
                    return OresScheduler.done(2);
                });
                assertTrue(started.await(3, TimeUnit.SECONDS));
                Thread canceller = new Thread(() -> child.cancel("racing"));
                canceller.start();
                release.countDown();
                canceller.join(3000);
                assertFalse(canceller.isAlive());
                TaskHandle.TaskOutcome<Integer> outcome =
                        child.getDone().get(3, TimeUnit.SECONDS);
                assertTrue(outcome instanceof TaskHandle.Success<?>
                        || outcome instanceof TaskHandle.Cancelled<?>);
                assertEquals(0, scope.liveCancellationChildren(),
                        "completed/cancelled child retained at iteration " + i);
            }
            scope.seal();
            scope.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test void schedulerRejectionDoesNotLeakScopeJoinSlots() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        TaskScope scope = scheduler.newTaskScope();
        scheduler.close();
        assertThrows(RejectedExecutionException.class,
                () -> scope.startTask(r -> OresScheduler.done(4)));
        scope.seal();
        scope.getDone().get(3, TimeUnit.SECONDS);
    }
    @Test void alreadyTerminalChildNeverPublishesScopeDoneUnderAdmissionLock()
            throws Exception {
        // The inline runtime-owned dispatcher intentionally completes a
        // child *before* startTask registers the terminal observer. This
        // forces the otherwise timing-sensitive subscription path.
        try (OresScheduler scheduler =
                     OresScheduler.runtimeOwned("inline-task-scope-regression",
                             1, Runnable::run)) {
            TaskScope scope = scheduler.newTaskScope();
            Field monitorField = TaskScope.class.getDeclaredField("admissionLock");
            monitorField.setAccessible(true);
            Object admissionMonitor = monitorField.get(scope);
            AtomicInteger notifications = new AtomicInteger();
            AtomicReference<Boolean> callbackHeldLock = new AtomicReference<>();
            AtomicReference<Boolean> guestHeldLock = new AtomicReference<>();
            scope.getDone().whenCompleteRuntime((ignored, failure) -> {
                notifications.incrementAndGet();
                callbackHeldLock.set(Thread.holdsLock(admissionMonitor));
            });

            // The guest turn seals its own scope. Since the inline dispatcher
            // completes before TaskScope subscribes to TaskHandle.getDone(),
            // the callback is delivered synchronously at registration.
            TaskHandle<Integer> child = scope.startTask(resume -> {
                guestHeldLock.set(Thread.holdsLock(admissionMonitor));
                scope.seal();
                return OresScheduler.done(7);
            });
            assertEquals(new TaskHandle.Success<>(7),
                    child.getDone().get(3, TimeUnit.SECONDS));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, notifications.get());
            assertEquals(Boolean.FALSE, guestHeldLock.get(),
                    "guest dispatch must never run under admissionLock");
            assertEquals(Boolean.FALSE, callbackHeldLock.get(),
                    "scope completion observers must not execute under admissionLock");
        }
    }

    @Test void blockedSchedulerAdmissionDoesNotBlockParentCancellation()
            throws Exception {
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        java.util.concurrent.Executor blockingExecutor = runnable -> {
            dispatchEntered.countDown();
            awaitUninterruptibly(releaseDispatch);
            runnable.run();
        };
        try (OresScheduler scheduler =
                     OresScheduler.runtimeOwned("blocked-admission-regression",
                             1, blockingExecutor)) {
            TaskScope scope = scheduler.newTaskScope();
            AtomicReference<TaskHandle<Integer>> admitted = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread submitter = new Thread(() -> {
                try {
                    admitted.set(scope.startTask(resume -> OresScheduler.done(123)));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            submitter.start();
            try {
                assertTrue(dispatchEntered.await(3, TimeUnit.SECONDS));
                CountDownLatch cancelled = new CountDownLatch(1);
                AtomicReference<Boolean> cancelledFirst = new AtomicReference<>();
                Thread canceller = new Thread(() -> {
                    try {
                        cancelledFirst.set(scope.cancel("scope shutdown"));
                    } finally {
                        cancelled.countDown();
                    }
                });
                canceller.start();
                assertTrue(cancelled.await(2, TimeUnit.SECONDS),
                        "scope.cancel must not wait on executor dispatch under admissionLock");
                assertTrue(cancelledFirst.get());
            } finally {
                releaseDispatch.countDown();
            }
            submitter.join(3000);
            assertFalse(submitter.isAlive());
            assertNull(failure.get());
            assertNotNull(admitted.get());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    admitted.get().getDone().get(3, TimeUnit.SECONDS));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void saturatedJoinCounterRejectsBeforeSignedOverflow()
            throws Exception {
        // White-box extreme-boundary test. This is not a production claim
        // about accepting billions of live tasks; it protects the invariant
        // that admitted children can never make the join count negative.
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            Field counter = TaskScope.class.getDeclaredField("active");
            counter.setAccessible(true);
            counter.setInt(scope, Integer.MAX_VALUE);
            assertThrows(RejectedExecutionException.class,
                    () -> scope.startTask(r -> OresScheduler.done(1)));
            assertEquals(Integer.MAX_VALUE, counter.getInt(scope),
                    "saturated admission must not mutate the join count");
            counter.setInt(scope, 0); // Restore test-only injected state.
            scope.seal();
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void rejectedDispatcherRacingScopeCancellationNeverPublishesPrematureJoin()
            throws Exception {
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseRejection = new CountDownLatch(1);
        java.util.concurrent.Executor rejectingDispatcher = runnable -> {
            dispatchEntered.countDown();
            awaitUninterruptibly(releaseRejection);
            throw new RejectedExecutionException("test dispatcher rejected");
        };
        try (OresScheduler scheduler =
                     OresScheduler.runtimeOwned("rejected-admission-race",
                             1, rejectingDispatcher)) {
            TaskScope scope = scheduler.newTaskScope();
            Field monitorField = TaskScope.class.getDeclaredField("admissionLock");
            monitorField.setAccessible(true);
            Object admissionMonitor = monitorField.get(scope);
            AtomicReference<Boolean> notificationHeldLock = new AtomicReference<>();
            scope.getDone().whenCompleteRuntime((ignored, failure) ->
                    notificationHeldLock.set(Thread.holdsLock(admissionMonitor)));
            AtomicReference<TaskHandle<Integer>> submitted = new AtomicReference<>();
            AtomicReference<Throwable> submitFailure = new AtomicReference<>();
            AtomicInteger guestRuns = new AtomicInteger();
            Thread submitter = new Thread(() -> {
                try {
                    submitted.set(scope.startTask(resume -> {
                        guestRuns.incrementAndGet();
                        return OresScheduler.done(42);
                    }));
                } catch (Throwable failure) {
                    submitFailure.set(failure);
                }
            });
            submitter.start();
            try {
                assertTrue(dispatchEntered.await(3, TimeUnit.SECONDS),
                        "expected scheduler to reach the blocked dispatcher");
                CountDownLatch cancelled = new CountDownLatch(1);
                AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
                Thread canceller = new Thread(() -> {
                    try { assertTrue(scope.cancel("shutdown")); }
                    catch (Throwable failure) { cancelFailure.set(failure); }
                    finally { cancelled.countDown(); }
                });
                canceller.start();
                assertTrue(cancelled.await(2, TimeUnit.SECONDS),
                        "scope cancellation must not wait for scheduler rejection");
                assertNull(cancelFailure.get());
                assertFalse(scope.getDone().isDone(),
                        "reserved child slot must keep scope join pending");
                canceller.join(3000);
                assertFalse(canceller.isAlive());
            } finally {
                releaseRejection.countDown();
            }
            submitter.join(3000);
            assertFalse(submitter.isAlive());
            if (submitFailure.get() != null) {
                assertInstanceOf(RejectedExecutionException.class, submitFailure.get());
            } else {
                assertNotNull(submitted.get());
                TaskHandle.TaskOutcome<Integer> outcome =
                        submitted.get().getDone().get(3, TimeUnit.SECONDS);
                assertTrue(outcome instanceof TaskHandle.Failed<?>
                        || outcome instanceof TaskHandle.Cancelled<?>,
                        "scheduler rejection must not appear as guest success");
            }
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, guestRuns.get());
            assertEquals(Boolean.FALSE, notificationHeldLock.get(),
                    "scope observers must not run under admissionLock");
        }
    }


    @Test void compilerOwnedReleaseWaitsForPhysicalScopeQuiescence()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch releaseGuest = new CountDownLatch(1);
            AtomicBoolean compilerLoanReleased = new AtomicBoolean();

            TaskHandle<Integer> child = scope.startTask(resume -> {
                entered.countDown();
                awaitUninterruptibly(releaseGuest);
                return OresScheduler.done(17);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            // Model the compiler/runtime ownership loan bridge: reclamation or
            // restoration of a transferred backing allocation is subscribed to
            // the scope's physical barrier, never to cancellation/result alone.
            scope.getDone().whenCompleteRuntime((ignored, failure) ->
                    compilerLoanReleased.set(true));

            assertTrue(scope.cancel("revoke child"));
            assertTrue(child.isCancellationRequested());
            assertFalse(compilerLoanReleased.get(),
                    "cancellation request must not release compiler ownership");
            assertFalse(scope.getDone().isDone(),
                    "scope done cannot publish while guest code is still running");

            releaseGuest.countDown();
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(compilerLoanReleased.get(),
                    "compiler ownership may release only after physical quiescence");
            assertInstanceOf(
                    TaskHandle.Cancelled.class,
                    child.getDone().get(3, TimeUnit.SECONDS));
        }
    }

}
