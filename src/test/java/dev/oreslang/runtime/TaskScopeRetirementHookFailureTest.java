package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression for a scope's post-quiescence ownership-release callback.
 *
 * <p>TaskHandle completion only proves the child has left its carrier. A
 * failed compiler-supplied release hook must not turn the parent's getDone()
 * into a successful reclamation witness. These are JVM host-runtime tests,
 * NOT proof of AttoLang guest or other backend execution.</p>
 */
public class TaskScopeRetirementHookFailureTest {
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

    @Test void failedOwnershipReleasePoisonsScopeButPreservesSuccessfulChild()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            IllegalStateException releaseFailure =
                    new IllegalStateException("owned region release failed");
            AtomicInteger callbacks = new AtomicInteger();

            TaskHandle<Integer> child = scope.startTask(
                    resume -> OresScheduler.done(7),
                    () -> {
                        callbacks.incrementAndGet();
                        throw releaseFailure;
                    });
            scope.seal();

            assertEquals(new TaskHandle.Success<>(7),
                    child.getDone().get(3, TimeUnit.SECONDS),
                    "logical child outcome is immutable");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> scope.getDone().get(3, TimeUnit.SECONDS),
                    "scope MUST NOT certify safe memory/loan reclamation");
            assertInstanceOf(TaskOwnedLeases.CleanupFailure.class,
                    failure.getCause());

            List<Throwable> retained = scope.cleanupFailures().get(
                    3, TimeUnit.SECONDS);
            assertEquals(1, retained.size());
            assertSame(releaseFailure, retained.get(0),
                    "retain the original compiler callback failure");
            var first = assertInstanceOf(TaskHandle.Failed.class,
                    scope.firstNonSuccessOutcome());
            assertSame(releaseFailure, first.failure());
            assertEquals(1, callbacks.get(), "physical release executes exactly once");
            assertFalse(scope.getDone().cancel(false),
                    "observer cannot clear a poisoned release witness");
        }
    }

    @Test void blockedOwnershipReleaseKeepsScopePendingUntilFailureIsObserved()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskScope scope = scheduler.newTaskScope();
            CountDownLatch inGuest = new CountDownLatch(1);
            CountDownLatch leaveGuest = new CountDownLatch(1);
            CountDownLatch enteredRelease = new CountDownLatch(1);
            CountDownLatch leaveRelease = new CountDownLatch(1);
            IllegalStateException releaseFailure =
                    new IllegalStateException("release deferred then failed");

            TaskHandle<Integer> child = scope.startTask(
                    resume -> {
                        inGuest.countDown();
                        awaitUninterruptibly(leaveGuest);
                        return OresScheduler.done(11);
                    },
                    () -> {
                        enteredRelease.countDown();
                        awaitUninterruptibly(leaveRelease);
                        throw releaseFailure;
                    });
            try {
                assertTrue(inGuest.await(3, TimeUnit.SECONDS));
                scope.seal();
                assertFalse(scope.getDone().isDone(),
                        "physical guest turn must finish before scope retirement");
                leaveGuest.countDown();
                assertTrue(enteredRelease.await(3, TimeUnit.SECONDS),
                        "expected post-quiescence hook to start");
                assertFalse(scope.getDone().isDone(),
                        "unfinished callback cannot release actor/borrowed memory");
                assertEquals(new TaskHandle.Success<>(11),
                        child.getDone().get(3, TimeUnit.SECONDS));
            } finally {
                leaveGuest.countDown();
                leaveRelease.countDown();
            }
            assertThrows(ExecutionException.class,
                    () -> scope.getDone().get(3, TimeUnit.SECONDS));
            List<Throwable> failures =
                    scope.cleanupFailures().get(3, TimeUnit.SECONDS);
            assertEquals(1, failures.size());
            assertSame(releaseFailure, failures.get(0));
        }
    }
}
