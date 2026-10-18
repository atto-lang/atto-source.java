package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
                assertTrue(a.result().isCancelled());
                assertTrue(b.result().isCancelled());
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

    @Test void schedulerRejectionDoesNotLeakScopeJoinSlots() throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        TaskScope scope = scheduler.newTaskScope();
        scheduler.close();
        assertThrows(RejectedExecutionException.class,
                () -> scope.startTask(r -> OresScheduler.done(4)));
        scope.seal();
        scope.getDone().get(3, TimeUnit.SECONDS);
    }
}
