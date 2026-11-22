package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Scheduler-level regressions, not just a standalone cancellation-tree model. */
public class TaskHandleTest {
    private static <T> TaskHandle.TaskOutcome<T> done(TaskHandle<T> handle) throws Exception {
        return handle.getDone().get(3, TimeUnit.SECONDS);
    }

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


    private static TaskCancellation authorityOf(TaskHandle<?> handle) throws Exception {
        java.lang.reflect.Field field = TaskHandle.class.getDeclaredField("cancellation");
        field.setAccessible(true);
        return (TaskCancellation) field.get(handle);
    }

    @Test void parentDoneAwaitsRunningChildPhysicalRetirement() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch parentRelease = new CountDownLatch(1);
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch childRelease = new CountDownLatch(1);
            TaskHandle<Integer> parent = scope.startTask(resume -> {
                parentEntered.countDown();
                awaitUninterruptibly(parentRelease);
                return OresScheduler.done(11);
            });
            assertTrue(parentEntered.await(3, TimeUnit.SECONDS));
            TaskHandle<Integer> child = TaskHandle.start(scheduler, resume -> {
                childEntered.countDown();
                awaitUninterruptibly(childRelease);
                return OresScheduler.done(22);
            }, authorityOf(parent));
            try {
                assertTrue(childEntered.await(3, TimeUnit.SECONDS));
                scope.seal();
                parentRelease.countDown();
                assertEquals(11, parent.result().get(3, TimeUnit.SECONDS));
                assertFalse(parent.getDone().isDone(),
                        "parent quiescence alone must not publish descendants");
                assertFalse(scope.getDone().isDone(),
                        "scope must inherit its child's physical lifetime barrier");
                assertThrows(IllegalStateException.class, authorityOf(parent)::child,
                        "finished parent's authority must seal new child admission");
            } finally {
                parentRelease.countDown();
                childRelease.countDown();
            }
            assertEquals(new TaskHandle.Success<>(22), done(child));
            assertEquals(new TaskHandle.Success<>(11), done(parent));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledParentStillAwaitsRunningChildPhysicalRetirement()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch parentEntered = new CountDownLatch(1);
            CountDownLatch parentRelease = new CountDownLatch(1);
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch childRelease = new CountDownLatch(1);
            TaskHandle<Integer> parent = scope.startTask(resume -> {
                parentEntered.countDown();
                awaitUninterruptibly(parentRelease);
                return OresScheduler.done(11);
            });
            assertTrue(parentEntered.await(3, TimeUnit.SECONDS));
            TaskHandle<Integer> child = TaskHandle.start(scheduler, resume -> {
                childEntered.countDown();
                awaitUninterruptibly(childRelease);
                return OresScheduler.done(22);
            }, authorityOf(parent));
            try {
                assertTrue(childEntered.await(3, TimeUnit.SECONDS));
                assertTrue(scope.cancel("stop descendants"));
                assertTrue(parent.isCancellationRequested());
                assertTrue(child.isCancellationRequested());
                parentRelease.countDown();
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> parent.result().get(3, TimeUnit.SECONDS));
                assertFalse(parent.getDone().isDone(),
                        "cancelled propagation edges are not quiescence");
                assertFalse(scope.getDone().isDone());
            } finally {
                parentRelease.countDown();
                childRelease.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class, done(child));
            assertInstanceOf(TaskHandle.Cancelled.class, done(parent));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void explicitCheckpointUnwindsFinallyAfterCancellationRequest()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean cleaned = new AtomicBoolean();
            AtomicBoolean workAfterCheckpoint = new AtomicBoolean();
            TaskHandle<Integer> handle = scheduler.startTracked(resume -> {
                try {
                    started.countDown();
                    awaitUninterruptibly(release);
                    assertTrue(OresScheduler.cancellationRequested());
                    OresScheduler.cancellationCheckpoint();
                    workAfterCheckpoint.set(true);
                    return OresScheduler.done(123);
                } finally {
                    cleaned.set(true);
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(handle.cancel("graceful checkpoint"));
                assertFalse(handle.result().isDone(),
                        "request must not asynchronously interrupt guest execution");
            } finally {
                release.countDown();
            }
            TaskHandle.TaskOutcome<Integer> outcome = done(handle);
            assertInstanceOf(TaskHandle.Cancelled.class, outcome);
            assertEquals("graceful checkpoint",
                    ((TaskHandle.Cancelled<Integer>) outcome).reason());
            assertTrue(cleaned.get(), "finally must execute before done");
            assertTrue(handle.result().isCancelled(),
                    "authenticated checkpoint should cancel the source Future too");
            assertFalse(workAfterCheckpoint.get(), "no work after safepoint");
        }
    }

    @Test void inlineUntrackedTaskDoesNotInheritOuterCancelledTaskDomain()
            throws Exception {
        // Inlining is used by VM-owned schedulers: a nested Task temporarily
        // replaces CURRENT_TASK_DOMAIN on the same Java carrier thread.
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "checkpoint-isolation", 1, Runnable::run)) {
            for (int i = 0; i < 100; i++) {
                TaskScope scope = scheduler.newTaskScope();
                AtomicInteger nested = new AtomicInteger();
                TaskHandle<Integer> outer = scope.startTask(resume -> {
                    assertTrue(scope.cancel("outer is stopping"));
                    assertTrue(OresScheduler.cancellationRequested());

                    OresFuture<Integer> plain = scheduler.start(inner -> {
                        assertFalse(OresScheduler.cancellationRequested(),
                                "untracked nested Task inherited outer cancellation");
                        OresScheduler.cancellationCheckpoint();
                        nested.incrementAndGet();
                        return OresScheduler.done(7);
                    });
                    assertEquals(7, plain.get(3, TimeUnit.SECONDS));
                    assertTrue(OresScheduler.cancellationRequested(),
                            "returning to outer domain must restore its token");
                    OresScheduler.cancellationCheckpoint();
                    return OresScheduler.done(99);
                });

                TaskHandle.TaskOutcome<Integer> outcome = done(outer);
                assertInstanceOf(TaskHandle.Cancelled.class, outcome);
                assertEquals(1, nested.get());
                assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
                assertFalse(OresScheduler.cancellationRequested());
            }
        }
    }

    @Test void nestedTrackedTaskHasOwnCancellationDomain() throws Exception {
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "nested-tracked-checkpoint", 1, Runnable::run)) {
            TaskScope scope = scheduler.newTaskScope();
            TaskHandle<Integer> outer = scope.startTask(resume -> {
                assertTrue(scope.cancel("outer cancelled"));
                assertTrue(OresScheduler.cancellationRequested());
                TaskHandle<Integer> nested = scheduler.startTracked(inner -> {
                    assertFalse(OresScheduler.cancellationRequested(),
                            "independent Task must not inherit ancestor token");
                    OresScheduler.cancellationCheckpoint();
                    return OresScheduler.done(15);
                });
                assertEquals(new TaskHandle.Success<>(15),
                        nested.getDone().get(3, TimeUnit.SECONDS));
                assertTrue(OresScheduler.cancellationRequested(),
                        "outer cancellation context must be restored");
                OresScheduler.cancellationCheckpoint();
                return OresScheduler.done(99);
            });
            assertInstanceOf(TaskHandle.Cancelled.class, done(outer));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cleanupExceptionOverridesPendingCooperativeCancellation()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(resume -> {
                try {
                    reached.countDown();
                    awaitUninterruptibly(release);
                    OresScheduler.cancellationCheckpoint();
                    return OresScheduler.done(44);
                } finally {
                    throw new IllegalStateException("cleanup failed");
                }
            });
            assertTrue(reached.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("graceful"));
            } finally {
                release.countDown();
            }
            TaskHandle.TaskOutcome<Integer> outcome = done(h);
            assertInstanceOf(TaskHandle.Failed.class, outcome,
                    "cleanup exception must not be swallowed by cancellation");
            assertInstanceOf(IllegalStateException.class,
                    ((TaskHandle.Failed<Integer>) outcome).failure());
            assertTrue(h.isCancellationRequested());
        }
    }

    @Test void checkpointNotifiesFutureAfterGuestContextHasUnwound()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch notified = new CountDownLatch(1);
            AtomicBoolean callbackSawGuestDomain = new AtomicBoolean();
            AtomicBoolean callbackSawGuestScheduler = new AtomicBoolean();
            TaskHandle<Integer> h = scheduler.startTracked(resume -> {
                started.countDown();
                awaitUninterruptibly(release);
                OresScheduler.cancellationCheckpoint();
                return OresScheduler.done(5);
            });
            h.result().whenCompleteRuntime((value, failure) -> {
                callbackSawGuestDomain.set(OresScheduler.currentTaskDomain() != null);
                callbackSawGuestScheduler.set(OresScheduler.current() != null);
                notified.countDown();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("checkpoint"));
                assertFalse(h.result().isDone());
            } finally {
                release.countDown();
            }
            assertTrue(notified.await(3, TimeUnit.SECONDS));
            assertTrue(h.result().isCancelled());
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
            assertFalse(callbackSawGuestDomain.get(),
                    "Future callbacks must not run inside the guest task domain");
            assertFalse(callbackSawGuestScheduler.get(),
                    "Future callbacks must not run inside bound scheduler turn");
        }
    }

    @Test void checkpointWithoutRequestDoesNotStopTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(resume -> {
                assertFalse(OresScheduler.cancellationRequested());
                OresScheduler.cancellationCheckpoint();
                return OresScheduler.done(8);
            });
            assertEquals(new TaskHandle.Success<>(8), done(handle));
            assertFalse(OresScheduler.cancellationRequested(),
                    "task-local cancellation token must not leak to host");
        }
    }

    @Test void checkpointPollOutsideTrackedTaskIsReadOnlyAndNoop() {
        assertFalse(OresScheduler.cancellationRequested());
        OresScheduler.cancellationCheckpoint();
        assertFalse(OresScheduler.cancellationRequested());
    }

    @Test void checkpointPollReturnsFalseAfterGuestTurnExit() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(resume -> {
                assertFalse(OresScheduler.cancellationRequested());
                return OresScheduler.done(9);
            });
            assertEquals(new TaskHandle.Success<>(9), done(handle));
            assertFalse(OresScheduler.cancellationRequested(),
                    "threadlocal must be cleared after success");
        }
    }

    @Test void successHasQuiescentOutcome() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(r -> OresScheduler.done(42));
            assertEquals(42, handle.result().get(3, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(42), done(handle));
        }
    }

    @Test void nullSuccessDoesNotLookLikePending() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Void> handle = scheduler.startTracked(r -> OresScheduler.done(null));
            assertEquals(new TaskHandle.Success<Void>(null), done(handle));
        }
    }

    @Test void failedTaskReportsFailureNotCancelled() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(r -> {
                throw new IllegalStateException("boom");
            });
            TaskHandle.TaskOutcome<Integer> outcome = done(handle);
            assertInstanceOf(TaskHandle.Failed.class, outcome);
            assertInstanceOf(IllegalStateException.class,
                    ((TaskHandle.Failed<Integer>) outcome).failure());
        }
    }

    @Test void runningTaskCancellationCannotPublishDoneBeforePhysicalExit() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean physicallyExited = new AtomicBoolean();
            TaskHandle<Integer> handle = scheduler.startTracked(r -> {
                entered.countDown();
                try {
                    awaitUninterruptibly(release);
                    return OresScheduler.done(7);
                } finally {
                    physicallyExited.set(true);
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(handle.cancel("shutdown"));
                assertTrue(handle.isCancellationRequested());
                assertFalse(handle.result().isDone(),
                        "running guest turn gets its cooperative cleanup checkpoint");
                assertFalse(handle.getDone().isDone());
                assertThrows(TimeoutException.class,
                        () -> handle.getDone().get(40, TimeUnit.MILLISECONDS));
                assertFalse(physicallyExited.get());
                assertFalse(handle.getDone().cancel(true),
                        "getDone is an observation-only Future");
            } finally {
                release.countDown();
            }
            TaskHandle.TaskOutcome<Integer> outcome = done(handle);
            assertInstanceOf(TaskHandle.Cancelled.class, outcome);
            assertEquals("shutdown", ((TaskHandle.Cancelled<Integer>) outcome).reason());
            assertTrue(physicallyExited.get());
        }
    }

    @Test void cancellationRequestRunsSynchronousFinallyBeforeTerminalSettlement()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean cleaned = new AtomicBoolean();
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                try {
                    inside.countDown();
                    awaitUninterruptibly(release);
                    return OresScheduler.done(8);
                } finally {
                    cleaned.set(true);
                }
            });
            assertTrue(inside.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("graceful stop"));
                assertFalse(h.result().isDone());
                assertFalse(h.getDone().isDone());
                assertFalse(cleaned.get());
            } finally {
                release.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
            assertTrue(cleaned.get());
            assertTrue(h.result().isCancelled());
        }
    }

    @Test void cancellationDuringRunningTurnSkipsNextCooperativeResume() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            AtomicBoolean cleaned = new AtomicBoolean();
            TaskHandle<Integer> h = scheduler.startTracked(resume -> {
                try {
                    if (turns.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitUninterruptibly(release);
                        return OresScheduler.cooperate();
                    }
                    return OresScheduler.done(100);
                } finally {
                    cleaned.set(true);
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("at cooperate boundary"));
                assertFalse(h.result().isDone());
            } finally {
                release.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
            assertEquals(1, turns.get(),
                    "cancellation must not re-enter the compiler state machine");
            assertTrue(cleaned.get());
        }
    }

    @Test void cancellationDuringRunningTurnDoesNotMaskGuestException() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                throw new IllegalArgumentException("failed before safe boundary");
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("stop requested"));
                assertFalse(h.result().isDone());
            } finally {
                release.countDown();
            }
            TaskHandle.TaskOutcome<Integer> outcome = done(h);
            assertInstanceOf(TaskHandle.Failed.class, outcome);
            assertInstanceOf(IllegalArgumentException.class,
                    ((TaskHandle.Failed<Integer>) outcome).failure());
            assertTrue(h.isCancellationRequested(),
                    "cancellation intent remains observable despite guest failure");
        }
    }

    @Test void awaitBoundaryCancelsPromptlyButLeavesBorrowedProducerAlone()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> borrowed = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                return OresScheduler.await(borrowed);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(h.cancel("suspended"));
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
            assertFalse(borrowed.isCancelled());
        }
    }

    @Test void completedTaskCannotBeRewrittenByLateCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> OresScheduler.done(11));
            assertEquals(new TaskHandle.Success<>(11), done(h));
            assertFalse(h.cancel("late"));
            assertEquals(new TaskHandle.Success<>(11), done(h));
        }
    }

    @Test void terminalTaskStillRejectsMalformedCancellationReasons() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> OresScheduler.done(11));
            assertEquals(new TaskHandle.Success<>(11), done(h));
            assertThrows(NullPointerException.class, () -> h.cancel(null));
            assertThrows(IllegalArgumentException.class, () -> h.cancel(" "));
            assertThrows(IllegalArgumentException.class, () -> h.cancel("x".repeat(257)));
            assertFalse(h.cancel("late"));
            assertEquals(new TaskHandle.Success<>(11), done(h));
        }
    }

    @Test void repeatCancellationPreservesFirstReason() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(99);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel("first"));
                assertFalse(h.cancel("second"));
            } finally {
                release.countDown();
            }
            assertEquals("first", ((TaskHandle.Cancelled<Integer>) done(h)).reason());
        }
    }

    @Test void cancellationBeforeQueuedTaskExecutesSkipsBody() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> blocker = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(1);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            AtomicInteger ran = new AtomicInteger();
            TaskHandle<Integer> queued = scheduler.startTracked(r -> {
                ran.incrementAndGet();
                return OresScheduler.done(2);
            });
            try {
                assertTrue(queued.cancel("queued"));
                assertInstanceOf(TaskHandle.Cancelled.class, done(queued));
                assertEquals(0, ran.get());
            } finally {
                release.countDown();
            }
            assertEquals(new TaskHandle.Success<>(1), done(blocker));
        }
    }

    @Test void cancellingWaitingFutureDetachesAndFinishes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> pending = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                suspended.countDown();
                return OresScheduler.await(pending);
            });
            assertTrue(suspended.await(3, TimeUnit.SECONDS));
            assertTrue(h.cancel("await-cancel"));
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
            assertFalse(pending.isCancelled(), "borrowed awaited Future remains independent");
        }
    }

    @Test void noImplicitFlatteningOfAwaitResult() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<OresFuture<Integer>> h = scheduler.startTracked(r ->
                    OresScheduler.done(OresFuture.completed(17)));
            TaskHandle.TaskOutcome<OresFuture<Integer>> outcome = done(h);
            assertInstanceOf(TaskHandle.Success.class, outcome);
            assertEquals(17,
                    ((TaskHandle.Success<OresFuture<Integer>>) outcome)
                            .value().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void foreignCancellationCannotForgeDone() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Void> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(null);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertFalse(h.getDone().cancel(false));
                assertFalse(h.getDone().isDone());
            } finally {
                release.countDown();
            }
            assertEquals(new TaskHandle.Success<Void>(null), done(h));
        }
    }

    @Test void cooperativeContinuationReturnsOneTerminalOutcome() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger turns = new AtomicInteger();
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                return turns.incrementAndGet() == 1
                        ? OresScheduler.cooperate()
                        : OresScheduler.done(8);
            });
            assertEquals(new TaskHandle.Success<>(8), done(h));
            assertEquals(2, turns.get());
        }
    }

    @Test void completedFutureAwaitResumesExactlyOnce() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger invocations = new AtomicInteger();
            TaskHandle<Integer> h = scheduler.startTracked(resume -> {
                if (invocations.incrementAndGet() == 1) {
                    return OresScheduler.await(OresFuture.completed(4));
                }
                return OresScheduler.done(((Integer) resume.value()) * 3);
            });
            assertEquals(new TaskHandle.Success<>(12), done(h));
            assertEquals(2, invocations.get());
        }
    }

    @Test void cancellationWithDefaultReasonIsObservable() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(5);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.cancel());
                assertTrue(h.isCancellationRequested());
            } finally {
                release.countDown();
            }
            assertEquals("cancelled", ((TaskHandle.Cancelled<Integer>) done(h)).reason());
        }
    }

    @Test void invalidReasonDoesNotCancelActiveTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(5);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertThrows(IllegalArgumentException.class, () -> h.cancel(" "));
                assertThrows(IllegalArgumentException.class, () -> h.cancel("x".repeat(257)));
                assertFalse(h.isCancellationRequested());
            } finally {
                release.countDown();
            }
            assertEquals(new TaskHandle.Success<>(5), done(h));
        }
    }

    @Test void directResultCancellationStillRequiresPhysicalQuiescence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                entered.countDown();
                awaitUninterruptibly(release);
                return OresScheduler.done(8);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(h.result().cancel(false));
                assertFalse(h.getDone().isDone());
                assertFalse(h.isCancellationRequested());
            } finally {
                release.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class, done(h));
        }
    }

    @Test void cannotStartTrackedTaskOnClosedScheduler() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> scheduler.startTracked(r -> OresScheduler.done(1)));
    }

    @Test void doneIsStableAcrossRepeatedObservers() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> OresScheduler.done(99));
            OresFuture<TaskHandle.TaskOutcome<Integer>> one = h.getDone();
            OresFuture<TaskHandle.TaskOutcome<Integer>> two = h.getDone();
            assertSame(one, two);
            assertEquals(new TaskHandle.Success<>(99), done(h));
            assertEquals(new TaskHandle.Success<>(99), one.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledParentBeforeTaskAdmissionProducesCancelledOutcome() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int i = 0; i < 100; i++) {
                TaskCancellation parent = new TaskCancellation();
                assertTrue(parent.cancel("parent shutdown"));
                AtomicBoolean guestRan = new AtomicBoolean();
                TaskHandle<Integer> h = TaskHandle.start(scheduler, resume -> {
                    guestRan.set(true);
                    return OresScheduler.done(5);
                }, parent);
                TaskHandle.TaskOutcome<Integer> outcome = done(h);
                assertInstanceOf(TaskHandle.Cancelled.class, outcome,
                        "pre-admission cancellation must not be treated as guest failure");
                assertEquals("parent shutdown",
                        ((TaskHandle.Cancelled<Integer>) outcome).reason());
                assertTrue(h.result().isCancelled(),
                        "pre-admission cancellation must settle source Future as cancelled");
                assertFalse(guestRan.get());
            }
        }
    }

    @Test void retiredAuthorityDoesNotRetainOrInvokeOldSubscriptions() {
        TaskCancellation parent = new TaskCancellation();
        TaskCancellation child = parent.child();
        AtomicInteger callbacks = new AtomicInteger();
        TaskCancellation.Subscription subscription =
                child.onCancel(callbacks::incrementAndGet);
        assertEquals(1, parent.ownedChildCount());
        child.retire(); // simulates a scheduler-confirmed completed task
        assertEquals(0, parent.ownedChildCount());
        assertFalse(child.isOwnedBy(parent));
        assertTrue(parent.cancel("parent stopped"));
        assertFalse(child.isCancelled());
        assertFalse(child.cancel("late direct cancellation"),
                "retired authority cannot be resurrected by a late cancel");
        assertFalse(child.isCancelled());
        assertEquals(0, callbacks.get());
        subscription.close();
    }

    @Test void reusedCheckpointExceptionCannotCancelUnrelatedTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            java.util.concurrent.atomic.AtomicReference<CancellationException> replayMarker =
                    new java.util.concurrent.atomic.AtomicReference<>();
            TaskHandle<Integer> original = scope.startTask(resume -> {
                assertTrue(scope.cancel("original stopping"));
                try {
                    OresScheduler.cancellationCheckpoint();
                } catch (CancellationException marker) {
                    replayMarker.set(marker);
                    throw marker;
                }
                return OresScheduler.done(1);
            });
            assertInstanceOf(TaskHandle.Cancelled.class, done(original));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));

            // A host can retain a Throwable and rethrow it in another Task.
            // Its runtime-private type alone is not cancellation authority.
            CancellationException captured = replayMarker.get();
            assertNotNull(captured, "the checkpoint marker must be captured");
            assertTrue(original.result().isCancelled(),
                    "authenticated checkpoint must settle the producer as cancelled");

            TaskHandle<Integer> unrelated = scheduler.startTracked(resume -> {
                throw captured;
            });
            TaskHandle.TaskOutcome<Integer> outcome = done(unrelated);
            assertInstanceOf(TaskHandle.Failed.class, outcome,
                    "a stale marker from another Task is not cancellation");
            assertFalse(unrelated.isCancellationRequested());
        }
    }

    @Test void serializedCheckpointMarkerCannotExportCancellationAuthority()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            java.util.concurrent.atomic.AtomicReference<CancellationException> captured =
                    new java.util.concurrent.atomic.AtomicReference<>();
            TaskHandle<Integer> owner = scope.startTask(resume -> {
                assertTrue(scope.cancel("serialize-safe"));
                try {
                    OresScheduler.cancellationCheckpoint();
                } catch (CancellationException checkpoint) {
                    captured.set(checkpoint);
                    throw checkpoint;
                }
                return OresScheduler.done(1);
            });
            assertInstanceOf(TaskHandle.Cancelled.class, done(owner));
            assertNull(scope.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(owner.result().isCancelled());

            var serialized = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.ObjectOutputStream(serialized)) {
                output.writeObject(captured.get());
            }
            CancellationException restored;
            try (var input = new java.io.ObjectInputStream(
                    new java.io.ByteArrayInputStream(serialized.toByteArray()))) {
                restored = assertInstanceOf(CancellationException.class,
                        input.readObject());
            }

            TaskHandle<Integer> unrelated = scheduler.startTracked(resume -> {
                throw restored;
            });
            assertInstanceOf(TaskHandle.Failed.class, done(unrelated),
                    "a deserialized checkpoint marker must not confer authority");
            assertFalse(unrelated.result().isCancelled());
        }
    }

    @Test void guestCancellationExceptionWithoutRequestIsFailure() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                throw new CancellationException("guest-thrown error");
            });
            TaskHandle.TaskOutcome<Integer> outcome = done(h);
            assertInstanceOf(TaskHandle.Failed.class, outcome);
            assertInstanceOf(CancellationException.class,
                    ((TaskHandle.Failed<Integer>) outcome).failure());
            assertFalse(h.isCancellationRequested());
        }
    }

    @Test void normalFailureDoesNotSetCancellationRequested() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                throw new IllegalArgumentException("bad input");
            });
            assertInstanceOf(TaskHandle.Failed.class, done(h));
            assertFalse(h.isCancellationRequested());
        }
    }

    @Test void cancellationAfterFailureDoesNotRewriteOutcome() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> {
                throw new IllegalArgumentException("failure");
            });
            TaskHandle.TaskOutcome<Integer> failed = done(h);
            assertInstanceOf(TaskHandle.Failed.class, failed);
            assertFalse(h.cancel("late"));
            assertEquals(failed, done(h));
        }
    }

    @Test void generatedLoopSafepointsStopPromptlyAndRunFinally() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                CountDownLatch entered = new CountDownLatch(1);
                AtomicBoolean cleaned = new AtomicBoolean();
                AtomicInteger checkpoints = new AtomicInteger();
                TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                    try {
                        entered.countDown();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        // Model codegen's periodic safe poll. No thread
                        // interruption or asynchronous exception injection.
                        while (System.nanoTime() < deadline) {
                            checkpoints.incrementAndGet();
                            OresScheduler.cancellationCheckpoint();
                            Thread.onSpinWait();
                        }
                        return OresScheduler.done(123);
                    } finally {
                        cleaned.set(true);
                    }
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(task.cancel("generated-loop checkpoint"));
                TaskHandle.TaskOutcome<Integer> outcome = done(task);
                assertInstanceOf(TaskHandle.Cancelled.class, outcome);
                assertTrue(cleaned.get(), "finally must finish before done");
                assertTrue(checkpoints.get() >= 1);
                assertTrue(task.result().isCancelled());
            }
        }
    }

    @Test void awaitProducerCompletionVersusCancellationNeverLeaksRegistration()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int iteration = 0; iteration < 200; iteration++) {
                OresFuture<Integer> producer = new OresFuture<>();
                CountDownLatch enteringAwait = new CountDownLatch(1);
                AtomicInteger turns = new AtomicInteger();
                TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                    turns.incrementAndGet();
                    if (resume.initial()) {
                        enteringAwait.countDown();
                        return OresScheduler.await(producer);
                    }
                    if (resume.failure() != null) {
                        throw new AssertionError("producer unexpectedly failed",
                                resume.failure());
                    }
                    return OresScheduler.done((Integer) resume.value());
                });
                assertTrue(enteringAwait.await(3, TimeUnit.SECONDS));
                AtomicReference<Throwable> cancellationError = new AtomicReference<>();
                Thread canceller = new Thread(() -> {
                    try {
                        task.cancel("await-or-cancel");
                    } catch (Throwable unexpected) {
                        cancellationError.set(unexpected);
                    }
                });
                canceller.start();
                assertTrue(producer.completeFromRuntime(iteration));
                canceller.join(3000);
                assertFalse(canceller.isAlive());
                assertNull(cancellationError.get());
                TaskHandle.TaskOutcome<Integer> outcome = done(task);
                assertTrue(outcome instanceof TaskHandle.Cancelled<?>
                                || outcome instanceof TaskHandle.Success<?>,
                        "await/cancel race cannot synthesize a Failed outcome");
                assertFalse(producer.isCancelled(),
                        "borrowed producer is never owned by consumer cancellation");
                assertEquals(0, producer.pendingRuntimeWaiterCount(),
                        "all await registrations must detach at quiescence");
                assertTrue(turns.get() >= 1 && turns.get() <= 2,
                        "a cancelled Task cannot resume more than once");
            }
        }
    }

    @Test void concurrentCheckpointAndCancellationKeepFutureAndOutcomeConsistent()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int iteration = 0; iteration < 500; iteration++) {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger cleanup = new AtomicInteger();
                TaskHandle<Integer> handle = scheduler.startTracked(resume -> {
                    try {
                        entered.countDown();
                        awaitUninterruptibly(release);
                        OresScheduler.cancellationCheckpoint();
                        return OresScheduler.done(73);
                    } finally {
                        cleanup.incrementAndGet();
                    }
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Thread canceller = new Thread(() -> handle.cancel("checkpoint race"));
                canceller.start();
                release.countDown();
                canceller.join(3000);
                assertFalse(canceller.isAlive());
                TaskHandle.TaskOutcome<Integer> outcome = done(handle);
                assertEquals(1, cleanup.get(), "guest finally must run once");
                assertTrue(outcome instanceof TaskHandle.Cancelled<?>
                        || outcome instanceof TaskHandle.Success<?>,
                        "cancellation/completion race cannot invent Failed");
                assertEquals(outcome instanceof TaskHandle.Cancelled<?>,
                        handle.result().isCancelled(),
                        "TaskHandle outcome and underlying Future must agree");
                if (outcome instanceof TaskHandle.Success<?>) {
                    assertEquals(73, handle.result().get(3, TimeUnit.SECONDS));
                } else {
                    assertThrows(CancellationException.class,
                            () -> handle.result().get(3, TimeUnit.SECONDS));
                }
                assertEquals(outcome, done(handle),
                        "getDone is an immutable physical-quiescence witness");
            }
        }
    }

    @Test void raceCancellationVersusCompletionAlwaysYieldsOneOutcome() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int i = 0; i < 100; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                TaskHandle<Integer> h = scheduler.startTracked(r -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return OresScheduler.done(1);
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Thread canceller = new Thread(() -> h.cancel("race"));
                canceller.start();
                release.countDown();
                canceller.join(3000);
                assertFalse(canceller.isAlive());
                TaskHandle.TaskOutcome<Integer> terminal = done(h);
                assertTrue(terminal instanceof TaskHandle.Success<?>
                        || terminal instanceof TaskHandle.Cancelled<?>);
                assertEquals(terminal, done(h));
            }
        }
    }
}
