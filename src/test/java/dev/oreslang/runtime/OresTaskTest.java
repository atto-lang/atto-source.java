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

}
