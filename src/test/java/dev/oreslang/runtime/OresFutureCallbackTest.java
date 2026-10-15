package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureCallbackTest {

    @Test
    void fromCallbackMaySettleSynchronouslyButRemainsSingleShot() throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();

        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            completion.set(callback);
            callback.resolve(41);
        });

        assertEquals(41, future.get(5, TimeUnit.SECONDS));
        assertTrue(completion.get().isDone());
        assertThrows(
                OresFuture.AlreadySettledException.class,
                () -> completion.get().resolve(42));
        assertEquals(41, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void lateRuntimeWaiterOnSettledFutureIsDeliveredAndReleased() {
        OresFuture<Integer> future = OresFuture.completed(42);
        AtomicReference<Integer> observed = new AtomicReference<>();

        future.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            observed.set(value);
        });

        assertEquals(42, observed.get());
        assertEquals(
                0,
                future.pendingRuntimeWaiterCount(),
                "late terminal registrations must not remain retained in the waiter queue");
    }

    @Test
    void competingSuccessFailureAndCancellationDeliverExactlyOneRuntimeNotification()
            throws Exception {
        // This is the underlying atomic waiter contract used by ActorRuntime's
        // continuation mailbox. A second per-registration completion gate is
        // redundant only if terminal arbitration and notification both stay
        // single-shot, including a racing consumer cancellation.
        final int rounds = 128;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            for (int round = 0; round < rounds; round++) {
                var source = new OresFuture<Integer>();
                var notified = new java.util.concurrent.atomic.AtomicInteger();
                var seenSuccess = new java.util.concurrent.atomic.AtomicInteger();
                var seenFailure = new java.util.concurrent.atomic.AtomicInteger();
                var callbackFailure = new AtomicReference<Throwable>();
                source.whenCompleteRuntime((value, failure) -> {
                    try {
                        if (failure == null) {
                            assertEquals(42, value);
                            seenSuccess.incrementAndGet();
                        } else {
                            assertNull(value);
                            seenFailure.incrementAndGet();
                        }
                    } catch (Throwable problem) {
                        callbackFailure.compareAndSet(null, problem);
                    } finally {
                        notified.incrementAndGet();
                    }
                });
                var ready = new java.util.concurrent.CountDownLatch(3);
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.List<java.util.concurrent.Callable<Boolean>> contenders =
                        java.util.List.of(
                                () -> source.completeFromRuntime(42),
                                () -> source.failFromRuntime(
                                        new IllegalStateException("competing failure")),
                                () -> source.cancel(false));
                java.util.List<java.util.concurrent.Future<Boolean>> results =
                        new java.util.ArrayList<>();
                for (var contender : contenders) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("settlement race barrier expired");
                        }
                        return contender.call();
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int winners = 0;
                for (var result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) winners++;
                }
                assertEquals(1, winners, "terminal CAS winner, round " + round);
                assertEquals(1, notified.get(), "runtime waiter was invoked twice");
                assertNull(callbackFailure.get(),
                        () -> String.valueOf(callbackFailure.get()));
                assertEquals(1, seenSuccess.get() + seenFailure.get());
                assertTrue(source.isDone());
                assertEquals(0, source.pendingRuntimeWaiterCount(),
                        "a settled future must not retain the actor's waiter");

                // Late registrations are notified once, synchronously as
                // host-only scheduler plumbing, and do not retain a node.
                source.whenCompleteRuntime((value, failure) ->
                        notified.incrementAndGet());
                assertEquals(2, notified.get());
                assertEquals(0, source.pendingRuntimeWaiterCount());
            }
        }
    }

    @Test
    void cancelledWaiterDoesNotPublishBeforeRevocationReturns() throws Exception {
        var cancelEntered = new java.util.concurrent.CountDownLatch(1);
        var permitRevocation = new java.util.concurrent.CountDownLatch(1);
        var source = new OresFuture<Integer>(() -> {
            cancelEntered.countDown();
            try {
                if (!permitRevocation.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("revocation gate expired");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        var observedFailure = new AtomicReference<Throwable>();
        var observedValue = new AtomicReference<Object>();
        source.whenCompleteRuntime((value, failure) -> {
            observedValue.set(value);
            observedFailure.set(failure);
            notifications.incrementAndGet();
        });
        var workerFailure = new AtomicReference<Throwable>();
        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                assertTrue(source.cancel(false));
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        });
        try {
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(source.isCancelled(),
                    "terminal CAS must become visible before revocation returns");
            source.whenCompleteRuntime((value, failure) ->
                    notifications.incrementAndGet());
            assertEquals(0, notifications.get(),
                    "late observers must not run while cancellation revocation is pending");
        } finally {
            permitRevocation.countDown();
        }
        canceller.join(5_000);
        assertFalse(canceller.isAlive());
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
        assertEquals(2, notifications.get());
        assertNull(observedValue.get());
        assertInstanceOf(java.util.concurrent.CancellationException.class,
                observedFailure.get());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void concurrentTerminalRegistrationAndDetachmentReclaimsClaimedWaiters()
            throws Exception {
        for (int round = 0; round < 64; round++) {
            var producer = new OresFuture<Integer>();
            var registered = new java.util.ArrayList<OresFuture.RuntimeWaiterRegistration<Integer>>();
            var gate = new java.util.concurrent.CountDownLatch(1);
            var workerFailure = new AtomicReference<Throwable>();
            Thread settler = Thread.startVirtualThread(() -> {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("settlement gate timed out");
                    }
                    assertTrue(producer.completeFromRuntime(43));
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            gate.countDown();
            for (int i = 0; i < 128; i++) {
                registered.add(producer.whenCompleteRuntimeCancellable((value, failure) -> {
                    assertNull(failure);
                    assertEquals(43, value);
                }));
            }
            settler.join(5_000);
            assertFalse(settler.isAlive(), "settlement stalled in round " + round);
            if (workerFailure.get() != null) {
                throw new AssertionError("settlement worker failed in round " + round,
                        workerFailure.get());
            }
            for (var waiter : registered) waiter.detach();
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "claimed and unclaimed waiter handles must not retain queue nodes");
            assertEquals(43, producer.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void settledWaiterCanReenterRegistrationWithoutLockingOrQueueRetention()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        producer.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            assertEquals(44, value);
            producer.whenCompleteRuntime((nested, nestedFailure) -> {
                assertNull(nestedFailure);
                assertEquals(44, nested);
                notifications.incrementAndGet();
            });
            notifications.incrementAndGet();
        });
        assertTrue(producer.completeFromRuntime(44));
        assertEquals(2, notifications.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        // Re-registering after the terminal drain must never add a waiter.
        for (int i = 0; i < 256; i++) {
            var handle = producer.whenCompleteRuntimeCancellable((value, failure) -> {
                assertNull(failure);
                assertEquals(44, value);
                notifications.incrementAndGet();
            });
            handle.detach();
        }
        assertEquals(258, notifications.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void consumerCancellationDropsFirstLateForeignCallbackButStillRejectsDuplicates() {
        AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();

        OresFuture<Integer> future = OresFuture.fromCallback(completion::set);

        assertTrue(future.cancel(false));
        assertTrue(future.isCancelled());
        assertTrue(completion.get().isDone());

        assertDoesNotThrow(() -> completion.get().resolve(42));
        assertTrue(future.isCancelled());

        assertThrows(
                OresFuture.AlreadySettledException.class,
                () -> completion.get().resolve(43));
    }

    @Test
    void registrarThrowRejectsFutureWhenCallbackHasNotSettled() {
        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            throw new IllegalStateException("registration failed");
        });

        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("registration failed", failure.getCause().getMessage());
    }

    @Test
    void attachedCallbackPreservesSharedSourceCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicBoolean registrarCalled = new AtomicBoolean();

            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> {
                        registrarCalled.set(true);
                        callback.resolve(value + 1);
                    });

            assertTrue(source.cancel(true));

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> chained.get(5, TimeUnit.SECONDS));
            assertTrue(chained.isCancelled());
            assertFalse(registrarCalled.get(),
                    "callback registrar must not run after its source was cancelled");
        }
    }

    @Test
    void attachedCallbackPreservesCallbackCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> chained = OresFuture.completed(40)
                    .attachCallback(
                            scheduler,
                            (value, callback) -> callback.cancel());

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> chained.get(5, TimeUnit.SECONDS));
            assertTrue(chained.isCancelled());
        }
    }

    @Test
    void cancellingAttachedChainDoesNotCancelSharedSourceAndDropsLateCallback()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = OresFuture.completed(40);
            AtomicReference<OresFuture.Callback<Integer>> callbackRef =
                    new AtomicReference<>();

            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> callbackRef.set(callback));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (callbackRef.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(callbackRef.get());

            assertTrue(chained.cancel(true));
            assertTrue(chained.isCancelled());
            assertFalse(source.isCancelled(),
                    "cancelling a callback chain must not cancel its shared source");

            OresFuture.Callback<Integer> callback = callbackRef.get();
            assertDoesNotThrow(() -> callback.resolve(41),
                    "first late foreign callback after chain cancellation is dropped");
            assertThrows(
                    OresFuture.AlreadySettledException.class,
                    () -> callback.resolve(42),
                    "a true duplicate callback remains a programming error");
        }
    }

    @Test
    void synchronousAttachedCallbackCannotCompleteChainOnRegistrarStack() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicBoolean insideRegistrar = new AtomicBoolean();
            AtomicBoolean completedInsideRegistrar = new AtomicBoolean();

            OresFuture<Integer> source = OresFuture.completed(40);
            OresFuture<Integer> chained = source.attachCallback(
                    scheduler,
                    (value, callback) -> {
                        insideRegistrar.set(true);
                        try {
                            callback.resolve(value + 2);
                        } finally {
                            insideRegistrar.set(false);
                        }
                    });

            chained.whenCompleteRuntime(
                    (value, failure) ->
                            completedInsideRegistrar.set(insideRegistrar.get()));

            assertEquals(42, chained.get(5, TimeUnit.SECONDS));
            assertFalse(
                    completedInsideRegistrar.get(),
                    "synchronous callback settlement must not recursively resume "
                            + "the dependent Future on the registrar stack");
        }
    }
}
