package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
                assertTrue(handle.result().isCancelled());
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

    @Test void completedTaskCannotBeRewrittenByLateCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> h = scheduler.startTracked(r -> OresScheduler.done(11));
            assertEquals(new TaskHandle.Success<>(11), done(h));
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
        assertTrue(child.cancel("late direct cancellation"));
        assertEquals(0, callbacks.get());
        subscription.close();
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
