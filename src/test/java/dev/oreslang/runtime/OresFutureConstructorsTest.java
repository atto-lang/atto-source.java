package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureConstructorsTest {
    @Test
    void asyncRunStartsOnRuntimeCarrierAndReturnsTypedValue() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresFuture<Integer> future = OresFutureConstructors.fromAsyncRun(
                    runtime, () -> {
                        assertTrue(AsyncRuntime.isAsyncCarrierThread());
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return 5 * 8;
                    });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            release.countDown();
            assertEquals(40, AsyncRuntime.await(future));
        }
    }

    @Test
    void asyncRunFailurePreservesOriginalFailure() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            IllegalStateException original = new IllegalStateException("run failed");
            OresFuture<Integer> future = OresFutureConstructors.fromAsyncRun(
                    runtime, () -> { throw original; });

            assertSame(original, assertThrows(
                    IllegalStateException.class, () -> AsyncRuntime.await(future)));
        }
    }

    @Test
    void callbackRunReturningWithoutSettlingKeepsFuturePending() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> resolver = new AtomicReference<>();
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                resolver::set);

        assertNotNull(resolver.get());
        assertFalse(future.isDone(), "returning from run must not resolve");
        resolver.get().resolve(5 * 8);
        assertEquals(40, future.get(5, TimeUnit.SECONDS));
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> resolver.get().resolve(41));
    }

    @Test
    void callbackRunFailureBeforeSettlementFailsFuture() {
        IllegalStateException original = new IllegalStateException("registration failed");
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                callback -> { throw original; });

        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertSame(original, failed.getCause());
    }

    @Test
    void callbackResolutionBeatsLaterRegistrarFailure() throws Exception {
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                callback -> {
                    callback.resolve(40);
                    throw new IllegalStateException("too late");
                });

        assertEquals(40, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancellationCannotBeUndoneByLateCallback() {
        AtomicReference<OresFuture.Callback<Integer>> resolver = new AtomicReference<>();
        OresFuture<Integer> future = OresFutureConstructors.fromRunCallback(
                resolver::set);

        assertTrue(future.cancel(false));
        assertDoesNotThrow(() -> resolver.get().resolve(40));
        assertTrue(future.isCancelled());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> resolver.get().resolve(41));
    }

    @Test
    void nullAdaptersAreRejected() {
        assertThrows(NullPointerException.class,
                () -> OresFutureConstructors.fromRunCallback(null));
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            assertThrows(NullPointerException.class,
                    () -> OresFutureConstructors.fromAsyncRun(runtime, null));
        }
    }

    @Test
    void cancelledCallbackRegistrationDetachesExactlyOnce() {
        AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    callback.set(cb);
                    return detaches::incrementAndGet;
                });

        assertFalse(future.isDone());
        assertEquals(0, detaches.get());
        assertTrue(future.cancel(false));
        assertEquals(1, detaches.get());
        assertDoesNotThrow(() -> callback.get().resolve(40));
        assertTrue(future.isCancelled());
        assertEquals(1, detaches.get());
    }

    @Test
    void synchronousResolutionCleansUpRegistrationAfterItIsInstalled()
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<String> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    cb.resolve("ready");
                    return detaches::incrementAndGet;
                });

        assertEquals("ready", future.get(5, TimeUnit.SECONDS));
        assertEquals(1, detaches.get());
    }

    @Test
    void rejectionCleansUpRegistration() throws Exception {
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        IllegalStateException failure = new IllegalStateException("producer failure");
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    cb.reject(failure);
                    return detaches::incrementAndGet;
                });

        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
        assertEquals(1, detaches.get());
    }

    @Test
    void concurrentCancellationAndProducerCompletionDoNotDoubleDetach()
            throws Exception {
        for (int i = 0; i < 128; i++) {
            AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
            OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                    cb -> {
                        callback.set(cb);
                        return detaches::incrementAndGet;
                    });
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread settle = Thread.startVirtualThread(() -> {
                try {
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("producer race gate timed out");
                    }
                    callback.get().resolve(40);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            Thread cancel = Thread.startVirtualThread(() -> {
                try {
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel race gate timed out");
                    }
                    future.cancel(false);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            go.countDown();
            settle.join(5_000);
            cancel.join(5_000);
            assertFalse(settle.isAlive(), "stalled producer in iteration " + i);
            assertFalse(cancel.isAlive(), "stalled canceller in iteration " + i);
            assertNull(unexpected.get(), "unexpected callback race exception");
            assertTrue(future.isDone());
            assertEquals(1, detaches.get(), "leaked or duplicate detach at iteration " + i);
        }
    }

    @Test
    void guestCallbackLoweringRequiresTrackedTaskTurn() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> OresFutureConstructors.fromRunCallbackOnCurrentTask(
                        callback -> callback.resolve(3)));

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                assertTrue(resume.initial());
                OresFuture<Integer> made =
                        OresFutureConstructors.fromRunCallbackOnCurrentTask(
                                callback -> {
                                    assertSame(scheduler, OresScheduler.current());
                                    callback.resolve(17);
                                });
                return OresScheduler.done(made.get(5, TimeUnit.SECONDS));
            });
            assertEquals(17, task.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellableRegistrationRejectsMissingCleanup() throws Exception {
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> null);
        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(NullPointerException.class, observed.getCause());
    }


    @Test
    void synchronousCallbackCannotPublishWithoutValidDetachRegistration()
            throws Exception {
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    cb.resolve(40);
                    return null;
                });

        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(NullPointerException.class, failed.getCause(),
                "invalid registration must fail, not expose the early success");
    }

    @Test
    void registrarFailureOverridesUnpublishedSynchronousResolution()
            throws Exception {
        IllegalArgumentException broken = new IllegalArgumentException("registration rollback");
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    cb.resolve(40);
                    throw broken;
                });

        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertSame(broken, failed.getCause(),
                "callbacks from a failed registrar must never escape registration");
    }


    @Test
    void cancellableResolverCannotResurrectCompletedFuture() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> external = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    external.set(cb);
                    return detaches::incrementAndGet;
                });

        external.get().resolve(40);
        assertEquals(40, future.get(5, TimeUnit.SECONDS));
        assertTrue(external.get().isDone());
        assertEquals(1, detaches.get());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> external.get().reject(new IllegalStateException("too late")));
        assertEquals(1, detaches.get());
    }

    @Test
    void cancelledCancellableResolverDiscardsFirstLateSettlementThenRejectsDuplicate() {
        AtomicReference<OresFuture.Callback<Integer>> external = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    external.set(cb);
                    return detaches::incrementAndGet;
                });
        assertTrue(future.cancel(false));
        assertTrue(external.get().isDone());
        assertDoesNotThrow(() -> external.get().resolve(40));
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> external.get().resolve(41));
        assertTrue(future.isCancelled());
        assertEquals(1, detaches.get());
    }

    @Test
    void failedCancellableRegistrarRevokesEscapedResolver() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> external = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("partial registration rolled back");
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                cb -> {
                    external.set(cb);
                    throw failure;
                });
        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
        assertTrue(external.get().isDone());
        assertDoesNotThrow(() -> external.get().resolve(40));
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> external.get().resolve(41));
    }


    @Test
    void synchronousProducerCancellationDetachesAfterRegistrationReturns() {
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                callback -> {
                    callback.cancel();
                    return detaches::incrementAndGet;
                });
        assertTrue(future.isCancelled());
        assertEquals(1, detaches.get());
    }

    @Test
    void asynchronousProducerCancellationDetachesExactlyOnce() {
        AtomicReference<OresFuture.Callback<Integer>> producer = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                callback -> {
                    producer.set(callback);
                    return detaches::incrementAndGet;
                });
        assertFalse(future.isDone());
        producer.get().cancel();
        assertTrue(future.isCancelled());
        assertEquals(1, detaches.get());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> producer.get().resolve(12));
        assertEquals(1, detaches.get());
    }

    @Test
    void cancellableRegistrarReturningDoesNotImplicitlySettle() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> producer = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                callback -> {
                    producer.set(callback);
                    return detaches::incrementAndGet;
                });
        assertFalse(future.isDone());
        assertFalse(producer.get().isDone());
        assertEquals(0, detaches.get());
        producer.get().resolve(123);
        assertEquals(123, future.get(5, TimeUnit.SECONDS));
        assertTrue(producer.get().isDone());
        assertEquals(1, detaches.get());
    }


    @Test
    void malformedAsynchronousNullRejectionFailsFutureAndDetaches() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> foreign = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                callback -> {
                    foreign.set(callback);
                    return detaches::incrementAndGet;
                });

        assertFalse(future.isDone());
        assertDoesNotThrow(() -> foreign.get().reject(null));
        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(NullPointerException.class, failed.getCause());
        assertEquals(1, detaches.get(), "malformed asynchronous rejection must not leak subscription");
        assertTrue(foreign.get().isDone());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> foreign.get().resolve(40));
    }

    @Test
    void malformedSynchronousNullRejectionDetachesAfterRegistration() throws Exception {
        java.util.concurrent.atomic.AtomicInteger detaches = new java.util.concurrent.atomic.AtomicInteger();
        OresFuture<Integer> future = OresFutureConstructors.fromCancellableRunCallback(
                callback -> {
                    callback.reject(null);
                    return detaches::incrementAndGet;
                });
        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(NullPointerException.class, failed.getCause());
        assertEquals(1, detaches.get(), "registration must detach after early malformed rejection");
    }

}
