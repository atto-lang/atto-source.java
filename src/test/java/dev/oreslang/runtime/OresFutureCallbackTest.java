package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
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
    @Test
    void hostStageRejectedCancellationCannotDiscardLaterValue() throws Exception {
        class CommittedHandoff<T> extends CompletableFuture<T> {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                // Mirrors a mutex waiter physically dequeued for handoff:
                // it cannot be cancelled even if the public result is pending.
                return false;
            }
        }
        CommittedHandoff<Object> handoff = new CommittedHandoff<>();
        OresFuture<Object> awaited = OresFuture.from(handoff);
        Object linearValue = new Object();

        assertFalse(awaited.cancel(false),
                "a rejected physical handoff must not disappear behind an OresFuture cancellation");
        assertFalse(awaited.isDone());
        handoff.complete(linearValue);
        assertSame(linearValue, awaited.get(2, TimeUnit.SECONDS));
        assertFalse(awaited.isCancelled());
    }

    @Test
    void minimalCompletionStageCannotBeCancelledButKeepsItsLateValue() throws Exception {
        CompletableFuture<Object> producer = new CompletableFuture<>();
        java.util.concurrent.CompletionStage<Object> minimal =
                producer.minimalCompletionStage();
        // This type implements Future but its cancel() throws
        // UnsupportedOperationException, unlike ordinary CompletableFuture.
        assertTrue(minimal instanceof java.util.concurrent.Future<?>);

        OresFuture<Object> awaited = OresFuture.from(minimal);
        Object linearResource = new Object();

        assertFalse(awaited.cancel(false),
                "unsupported producer cancellation must not publish a cancelled consumer");
        assertFalse(awaited.isDone());
        producer.complete(linearResource);
        assertSame(linearResource, awaited.get(2, TimeUnit.SECONDS));
        assertFalse(awaited.isCancelled());
    }

    @Test
    void independentlyCancelledMinimalStagePropagatesWithoutStalling() throws Exception {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        java.util.concurrent.CompletionStage<Integer> minimal =
                producer.minimalCompletionStage();
        OresFuture<Integer> awaited = OresFuture.from(minimal);

        assertTrue(producer.cancel(false));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> awaited.get(2, TimeUnit.SECONDS),
                "minimal CompletionStage throws from isCancelled(); adapter must use the terminal failure");
        assertTrue(awaited.isCancelled());
    }

    @Test
    void exceptionallyCompletedMinimalStageKeepsFailureIdentity() throws Exception {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        java.util.concurrent.CompletionStage<Integer> minimal =
                producer.minimalCompletionStage();
        OresFuture<Integer> awaited = OresFuture.from(minimal);
        IllegalStateException failure = new IllegalStateException("minimal-source-failure");

        assertTrue(producer.completeExceptionally(failure));
        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> awaited.get(2, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
        assertFalse(awaited.isCancelled(), "an ordinary failure must not become cancellation");
    }

    @Test
    void acceptedHostStageCancellationPropagatesExactlyOnce() {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        OresFuture<Integer> awaited = OresFuture.from(producer);

        assertTrue(awaited.cancel(false));
        assertTrue(producer.isCancelled());
        assertTrue(awaited.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, awaited::join);
        assertFalse(awaited.cancel(false), "an already settled consumer cannot claim cancellation twice");
    }

    @Test
    void independentlyCancelledHostStageSettlesAwaitableWithoutReentry() {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        OresFuture<Integer> awaited = OresFuture.from(producer);

        assertTrue(producer.cancel(false));
        assertTrue(awaited.isCancelled());
        assertThrows(java.util.concurrent.CancellationException.class, awaited::join);
    }

    @Test
    void producerThatCompletedBeforeCancellationKeepsItsValue() throws Exception {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        OresFuture<Integer> awaited = OresFuture.from(producer);
        producer.complete(42);
        assertFalse(awaited.cancel(false));
        assertEquals(42, awaited.get(2, TimeUnit.SECONDS));
    }

}
