package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class RecursiveFutureFlattenTest {
    @Test
    void pendingThreeDeepFutureAdoptsFinalPayloadOnlyAfterInnerSettles() {
        OresFuture<Object> outer = new OresFuture<>();
        OresFuture<Object> middle = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        OresFuture<Integer> flat = OresFutures.flatten(outer);

        assertEquals(1, outer.pendingRuntimeWaiterCount());
        outer.completeFromRuntime(middle);
        assertFalse(flat.isDone());
        middle.completeFromRuntime(inner);
        assertFalse(flat.isDone());
        inner.completeFromRuntime(42);
        assertEquals(42, flat.join());
        assertEquals(0, outer.pendingRuntimeWaiterCount());
        assertEquals(0, middle.pendingRuntimeWaiterCount());
        assertEquals(0, inner.pendingRuntimeWaiterCount());
    }

    @Test
    void settledDepthDoesNotOverflowJavaStack() {
        OresFuture<?> chain = OresFuture.completed(77);
        for (int i = 0; i < 2048; i++) chain = OresFuture.completed(chain);
        OresFuture<Integer> flattened = OresFutures.flatten(chain);
        assertEquals(77, flattened.join());
    }

    @Test
    void innerFailureAndCancellationPropagateWithoutBecomingValues() {
        OresFuture<Object> failedOuter = new OresFuture<>();
        OresFuture<Integer> failedInner = new OresFuture<>();
        OresFuture<Integer> failed = OresFutures.flatten(failedOuter);
        failedOuter.completeFromRuntime(failedInner);
        failedInner.failFromRuntime(new IllegalArgumentException("bad"));
        CompletionException error = assertThrows(CompletionException.class, failed::join);
        assertInstanceOf(IllegalArgumentException.class, error.getCause());

        OresFuture<Object> cancelledOuter = new OresFuture<>();
        OresFuture<Integer> cancelledInner = new OresFuture<>();
        OresFuture<Integer> cancelled = OresFutures.flatten(cancelledOuter);
        cancelledOuter.completeFromRuntime(cancelledInner);
        cancelledInner.cancel(false);
        assertTrue(cancelled.isCancelled());
        assertThrows(CancellationException.class, cancelled::join);
    }

    @Test
    void cancellingFlattenedObserverDetachesWithoutCancellingSharedProducer() {
        OresFuture<Integer> shared = new OresFuture<>();
        OresFuture<Integer> observed = OresFutures.flatten(shared);
        assertEquals(1, shared.pendingRuntimeWaiterCount());
        assertTrue(observed.cancel(false));
        assertEquals(0, shared.pendingRuntimeWaiterCount());
        assertFalse(shared.isCancelled());
        shared.completeFromRuntime(10);
        assertTrue(observed.isCancelled());
    }

    @Test
    void cancellationRacingWithNestedSubscriptionDetachesEveryWaiter()
            throws InterruptedException {
        for (int round = 0; round < 256; round++) {
            OresFuture<Object> outer = new OresFuture<>();
            OresFuture<Object> middle = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            OresFuture<Integer> flattened = OresFutures.flatten(outer);

            java.util.concurrent.CountDownLatch begin =
                    new java.util.concurrent.CountDownLatch(1);
            Thread publisher = Thread.startVirtualThread(() -> {
                try {
                    begin.await();
                    outer.completeFromRuntime(middle);
                    middle.completeFromRuntime(inner);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    begin.await();
                    flattened.cancel(false);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            begin.countDown();
            publisher.join(5_000);
            canceller.join(5_000);
            assertFalse(publisher.isAlive(), "publisher stalled in round " + round);
            assertFalse(canceller.isAlive(), "canceller stalled in round " + round);
            assertTrue(flattened.isCancelled(), "round " + round);
            assertEquals(0, outer.pendingRuntimeWaiterCount(), "outer round " + round);
            assertEquals(0, middle.pendingRuntimeWaiterCount(), "middle round " + round);
            assertEquals(0, inner.pendingRuntimeWaiterCount(), "inner round " + round);
            assertFalse(outer.isCancelled());
            assertFalse(middle.isCancelled());
            assertFalse(inner.isCancelled());
        }
    }

    @Test
    void earlyInnerCancellationNeverResurrectsFlattenedResult() {
        OresFuture<Object> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        OresFuture<Integer> flattened = OresFutures.flatten(outer);
        outer.completeFromRuntime(inner);
        assertTrue(inner.cancel(false));
        assertTrue(flattened.isCancelled());
        assertThrows(CancellationException.class, flattened::join);
        assertEquals(0, inner.pendingRuntimeWaiterCount());
        assertFalse(flattened.cancel(false));
    }

    @Test
    void cyclicNestedFutureFailsClosedInsteadOfWaitingForever() {
        OresFuture<Object> self = new OresFuture<>();
        OresFuture<Integer> flattened = OresFutures.flatten(self);
        self.completeFromRuntime(self);
        CompletionException error = assertThrows(CompletionException.class, flattened::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("cyclic"));
    }
}
