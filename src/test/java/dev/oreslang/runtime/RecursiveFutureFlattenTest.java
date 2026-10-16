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
    void cyclicNestedFutureFailsClosedInsteadOfWaitingForever() {
        OresFuture<Object> self = new OresFuture<>();
        OresFuture<Integer> flattened = OresFutures.flatten(self);
        self.completeFromRuntime(self);
        CompletionException error = assertThrows(CompletionException.class, flattened::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("cyclic"));
    }
}
