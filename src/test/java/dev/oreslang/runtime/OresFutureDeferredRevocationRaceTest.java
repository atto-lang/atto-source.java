package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Deferred cancellation may settle inside, or race against, its revoker. */
final class OresFutureDeferredRevocationRaceTest {
    @Test
    @Timeout(15)
    void competingCompletionCannotExposeCancellationBeforeRevocationReturns()
            throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        AtomicInteger ownerReleases = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        OresFuture<Integer> future = new OresFuture<>(() -> {
            hookEntered.countDown();
            try {
                if (!releaseHook.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("revoker release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }, ownerReleases::incrementAndGet, true);
        future.whenCompleteRuntime((value, failure) -> notifications.incrementAndGet());

        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                if (!future.cancel(false)) throw new AssertionError("cancel not admitted");
            } catch (Throwable e) {
                workerFailure.compareAndSet(null, e);
            }
        });
        try {
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            assertTrue(future.completeFromRuntime(27),
                    "cleanup may claim a terminal cancellation during revocation");
            assertTrue(future.isCancelled(), "terminal claim is already visible");
            assertNull(future.runtimeTerminalStateOrNull(),
                    "await readiness must wait for producer revocation");
            assertNull(future.runtimeSelectReadyStateOrNull(),
                    "select must not observe half-revoked cancellation");
            future.whenCompleteRuntime((value, failure) -> notifications.incrementAndGet());
            assertEquals(0, notifications.get(), "no callback may run before revocation");
            assertEquals(0, ownerReleases.get(), "owner release follows waiter delivery");
        } finally {
            releaseHook.countDown();
        }
        canceller.join(5_000);
        assertFalse(canceller.isAlive(), "revocation hung");
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
        assertInstanceOf(CancellationException.class,
                OresFuture.runtimeTerminalFailure(future.runtimeTerminalStateOrNull()));
        assertEquals(2, notifications.get());
        assertEquals(1, ownerReleases.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
        assertFalse(future.completeFromRuntime(28));
        assertFalse(future.cancel(false));
    }

    @Test
    void reentrantCompletionAndRegistrationDoNotNotifyInsideRevoker() {
        AtomicReference<OresFuture<Integer>> owned = new AtomicReference<>();
        AtomicBoolean completedInsideHook = new AtomicBoolean();
        AtomicBoolean notifiedInsideHook = new AtomicBoolean();
        AtomicInteger notifications = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(() -> {
            OresFuture<Integer> current = owned.get();
            completedInsideHook.set(current.completeDeferredCancellationFromRuntime());
            if (notifications.get() != 0) notifiedInsideHook.set(true);
            current.whenCompleteRuntime((value, failure) -> notifications.incrementAndGet());
            if (notifications.get() != 0) notifiedInsideHook.set(true);
        }, releases::incrementAndGet, true);
        owned.set(future);
        future.whenCompleteRuntime((value, failure) -> notifications.incrementAndGet());

        assertTrue(future.cancel(false));
        assertTrue(completedInsideHook.get());
        assertFalse(notifiedInsideHook.get(),
                "callbacks must not run inside the still-active cancellation revoker");
        assertTrue(future.isCancelled());
        assertNotNull(future.runtimeSelectReadyStateOrNull());
        assertEquals(2, notifications.get());
        assertEquals(1, releases.get());
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }

    @Test
    @Timeout(20)
    void concurrentCompletionAndCancellationNeverDuplicateNotifications() throws Exception {
        for (int round = 0; round < 128; round++) {
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger notifications = new AtomicInteger();
            AtomicInteger releases = new AtomicInteger();
            AtomicInteger revocations = new AtomicInteger();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            OresFuture<Integer> future = new OresFuture<>(
                    revocations::incrementAndGet, releases::incrementAndGet, true);
            future.whenCompleteRuntime((value, failure) -> notifications.incrementAndGet());
            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancel gate timed out");
                    }
                    future.cancel(false);
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            Thread completer = Thread.startVirtualThread(() -> {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("completion gate timed out");
                    }
                    future.completeFromRuntime(42);
                } catch (Throwable failure) {
                    workerFailure.compareAndSet(null, failure);
                }
            });
            gate.countDown();
            canceller.join(5_000);
            completer.join(5_000);
            assertFalse(canceller.isAlive(), "canceller hung in round " + round);
            assertFalse(completer.isAlive(), "completer hung in round " + round);
            assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
            assertTrue(future.isDone());
            assertEquals(1, notifications.get(), "duplicate/missing callback in round " + round);
            assertEquals(1, releases.get(), "duplicate/missing release in round " + round);
            assertTrue(revocations.get() <= 1);
            assertEquals(0, future.pendingRuntimeWaiterCount());
            assertNotNull(future.runtimeTerminalStateOrNull());
        }
    }
}
