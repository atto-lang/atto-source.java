package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class FutureObservationRetentionTest {
    @Test
    void schedulerFastObservationWaitsForCancellationRevocation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicBoolean notified = new AtomicBoolean();
        OresFuture<Integer> pending = new OresFuture<>(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            revoked.set(true);
        });
        Thread cancelling = Thread.ofVirtual().start(() -> pending.cancel(false));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(pending.isCancelled(), "host cancellation observation remains immediate");
            assertNull(pending.runtimeTerminalStateOrNull(),
                    "scheduler fast path must not bypass operation revocation");
            pending.whenCompleteRuntime((value, failure) -> notified.set(revoked.get()));
            assertFalse(notified.get());
        } finally {
            release.countDown();
            cancelling.join(2_000);
        }
        assertFalse(cancelling.isAlive());
        assertTrue(notified.get());
        assertNotNull(pending.runtimeTerminalStateOrNull());
    }

    @Test
    void repeatedTimeoutsDetachHostObservationsWithoutCancellingProducer() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        for (int attempt = 0; attempt < 32; attempt++) {
            assertThrows(TimeoutException.class, () -> pending.get(1, TimeUnit.NANOSECONDS));
            assertEquals(0, pending.pendingRuntimeWaiterCount());
        }
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(42));
        assertEquals(42, pending.get());
    }

    @Test
    void interruptedHostReadDetachesObservationWithoutCancellingProducer() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, pending::get);
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, pending.pendingRuntimeWaiterCount());
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(42));
        assertEquals(42, pending.get());
    }

    @Test
    void allSettledCancellationDetachesWithoutCancellingBorrowedChildren() throws Exception {
        // allSettled is a nonowning observer. Its cancellation detaches only
        // subscriptions; the caller's TaskScope retains child cancel authority.
        OresFuture<Integer> uncancellable = new OresFuture<>(() -> false, () -> fail("not admitted"));
        OresFuture<Integer> cancellable = new OresFuture<>();
        OresFuture<List<OresFutures.Settled<Integer>>> aggregate =
                OresFutures.allSettled(List.of(uncancellable, cancellable));
        assertEquals(1, uncancellable.pendingRuntimeWaiterCount());
        assertEquals(1, cancellable.pendingRuntimeWaiterCount());
        assertTrue(aggregate.cancel(false));
        assertEquals(0, uncancellable.pendingRuntimeWaiterCount());
        assertEquals(0, cancellable.pendingRuntimeWaiterCount());
        assertFalse(uncancellable.isCancelled());
        assertFalse(cancellable.isCancelled(),
                "cancelling a borrowed observer must not revoke an independently owned child");
        assertTrue(uncancellable.completeFromRuntime(42));
        assertTrue(cancellable.completeFromRuntime(7));
        assertEquals(42, uncancellable.get());
        assertEquals(7, cancellable.get());
        assertTrue(aggregate.isCancelled());
    }
}
