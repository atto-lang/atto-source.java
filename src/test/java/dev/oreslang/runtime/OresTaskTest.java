package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTaskTest {
    @Test
    void valueTaskIsAwaitableWithoutBlockingTheCallerAtLaunch() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresTask<Integer> task = runtime.submitTask(() -> {
                started.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return 42;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            release.countDown();
            assertEquals(42, AsyncRuntime.await(task.getAwaited()));
            assertTrue(task.isDone());
        }
    }

    @Test
    void voidTaskIsStillAwaitable() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTask<Void> task = runtime.submitTask(() -> null);
            assertNull(AsyncRuntime.await(task.getAwaited()));
            assertTrue(task.isDone());
        }
    }

    @Test
    void detachedTaskHasNoHandleButOwnsItsFailureReporting() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch reported = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            IllegalStateException original = new IllegalStateException("task failed");
            runtime.submitDetached(() -> {
                throw original;
            }, problem -> {
                failure.set(problem);
                reported.countDown();
            });
            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertSame(original, failure.get());
        }
    }

    @Test
    void detachedTaskReportsCancellationOnContextShutdown() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            runtime.submitDetached(() -> {
                started.countDown();
                Thread.sleep(30_000);
                return null;
            }, problem -> {
                failure.set(problem);
                reported.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            runtime.close();
            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertNotNull(failure.get());
        } finally {
            if (!runtime.isClosed()) runtime.close();
        }
    }

    @Test
    void detachedTasksRequireErrorSupervisor() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            assertThrows(NullPointerException.class,
                    () -> runtime.submitDetached(() -> null, null));
        }
    }
    @Test
    void explicitFutureProjectionDoesNotCreateSecondTaskOrFutureLayer() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTask<Integer> task = runtime.submitTask(() -> 9);
            OresFuture<Integer> observed = task.asFuture();
            assertSame(task.getAwaited(), observed);
            assertEquals(9, AsyncRuntime.await(observed));
        }
    }

    @Test
    void futureProjectionRetainsCancellationAuthority() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            OresTask<Integer> task = runtime.submitTask(() -> {
                started.countDown();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    return 7;
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(task.asFuture().cancel(true));
            assertTrue(task.isCancelled());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }


    @Test
    void retirementFailureReachesEachLiveDoneObserverButNotTaskResult() throws Exception {
        OresFuture<Integer> result = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresTask<Integer> task = new OresTask<>(result, physicalExit);
        OresFuture<Void> abandoned = task.getDone();
        OresFuture<Void> observing = task.getDone();
        assertTrue(abandoned.cancel(true));
        IllegalStateException failedRelease =
                new IllegalStateException("trusted image retirement failed");
        assertTrue(result.completeFromRuntime(42));
        assertTrue(physicalExit.failFromRuntime(failedRelease));
        assertEquals(42, result.get());
        java.util.concurrent.ExecutionException observed =
                assertThrows(java.util.concurrent.ExecutionException.class, observing::get);
        assertSame(failedRelease, observed.getCause(),
                "quiescence must propagate a failed trusted resource release");
        assertTrue(abandoned.isCancelled());
        assertEquals(0, physicalExit.pendingRuntimeWaiterCount(),
                "settled or abandoned observers must detach");
    }

    @Test
    void taskCancellationDoesNotTurnSuccessfulPhysicalCleanupIntoFailure()
            throws Exception {
        OresFuture<Integer> result = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresTask<Integer> task = new OresTask<>(result, physicalExit);
        OresFuture<Void> joined = task.cancelAndJoin();
        assertTrue(task.isCancelled());
        assertFalse(joined.isDone(), "cancellation is not proof of cleanup");
        assertTrue(physicalExit.completeFromRuntime(null));
        assertNull(joined.get(), "successful physical cleanup remains a void barrier");
    }

}
