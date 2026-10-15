package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the shared mainline/PR887 Future terminal contract after integration. */
final class OresFutureIntegratedCancellationContractTest {
    @Test
    void deferredCancellationRetainsLexicalCleanupBarrierAndReleasesSubscribersOnce() {
        var revocations = new AtomicInteger();
        var drained = new AtomicInteger();
        var observed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        OresFuture<Integer> future = new OresFuture<>(
                revocations::incrementAndGet, drained::incrementAndGet, true);
        future.whenCompleteRuntime((value, error) -> {
            assertNull(value);
            failure.set(error);
            observed.incrementAndGet();
        });

        assertTrue(future.cancel(false));
        assertFalse(future.isDone(), "cancellation request is not cleanup completion");
        assertFalse(future.isCancelled(), "terminal cancellation awaits lexical cleanup");
        assertNull(future.runtimeSelectReadyStateOrNull(),
                "future select must not precommit a pending cleanup");
        assertEquals(0, observed.get(), "no terminal subscribers may fire before cleanup");
        assertEquals(1, revocations.get());

        assertTrue(future.completeDeferredCancellationFromRuntime());
        assertTrue(future.isCancelled());
        assertInstanceOf(CancellationException.class, failure.get());
        assertEquals(1, observed.get());
        assertEquals(1, drained.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
        assertFalse(future.completeFromRuntime(42), "late producer result is inert");
        assertFalse(future.cancel(false), "cancel is single shot");
        assertEquals(1, revocations.get());
        assertEquals(1, drained.get());
    }

    @Test
    void ordinaryCompletionCannotOverrideAlreadyRequestedDeferredCancellation() {
        var released = new AtomicInteger();
        var revocations = new AtomicInteger();
        var future = new OresFuture<Integer>(
                revocations::incrementAndGet, released::incrementAndGet, true);
        assertTrue(future.cancel(false));
        assertTrue(future.completeFromRuntime(17));
        assertTrue(future.isCancelled(),
                "completion is a cleanup terminal signal, not a winning result");
        assertThrows(CancellationException.class, future::join);
        assertEquals(1, revocations.get());
        assertEquals(1, released.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }

    @Test
    @Timeout(10)
    void selectingCancelledFutureWaitsUntilTheInternalRevokerFinishes() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observed = new AtomicInteger();
        var cancellationFailure = new AtomicReference<Throwable>();
        var future = new OresFuture<Integer>(() -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("revoker gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        future.whenCompleteRuntime((value, failure) -> {
            assertInstanceOf(CancellationException.class, failure);
            observed.incrementAndGet();
        });
        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                assertTrue(future.cancel(false));
            } catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(future.isCancelled(), "terminal claim is visible immediately");
            assertNull(future.runtimeTerminalStateOrNull(),
                    "await fast path must respect cancellation revocation barrier");
            assertNull(future.runtimeSelectReadyStateOrNull(),
                    "select readiness must not observe half-revoked producer");
            assertEquals(0, observed.get(), "runtime observer may not run before revocation");
        } finally {
            release.countDown();
        }
        canceller.join(5_000);
        assertFalse(canceller.isAlive());
        assertNull(cancellationFailure.get(), () -> String.valueOf(cancellationFailure.get()));
        assertNotNull(future.runtimeSelectReadyStateOrNull());
        assertEquals(1, observed.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }
}
