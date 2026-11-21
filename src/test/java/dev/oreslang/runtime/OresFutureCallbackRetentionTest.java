package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A callback registrar may retain a completion capability indefinitely.
 * Regression tests prove observable settlement and cancellation semantics;
 * actual garbage-collection timing is deliberately not asserted.
 */
final class OresFutureCallbackRetentionTest {

    @Test
    void retainedForeignCallbackDoesNotResurrectCancelledObserver() {
        AtomicReference<OresFuture.Callback<Integer>> retained =
                new AtomicReference<>();
        OresFuture<Integer> observer = OresFuture.fromCallback(retained::set);
        OresFuture.Callback<Integer> foreign = retained.get();

        assertNotNull(foreign);
        assertFalse(foreign.isDone());
        assertTrue(observer.cancel(false));
        assertTrue(foreign.isDone());

        // First late producer event is deliberately ignored, even though its
        // owner no longer has permission to settle the cancelled observer.
        assertDoesNotThrow(() -> foreign.resolve(42));
        assertTrue(observer.isCancelled());
        assertThrows(CancellationException.class, observer::join);
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> foreign.resolve(99),
                "the second producer notification must still be rejected");
    }

    @Test
    void retainedForeignCallbackStillCompletesStronglyHeldLiveObserver() {
        AtomicReference<OresFuture.Callback<Integer>> retained =
                new AtomicReference<>();
        OresFuture<Integer> observer = OresFuture.fromCallback(retained::set);
        OresFuture.Callback<Integer> foreign = retained.get();

        assertNotNull(foreign);
        assertFalse(foreign.isDone());
        foreign.resolve(21);
        assertEquals(21, observer.join());
        assertTrue(foreign.isDone());
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> foreign.reject(new IllegalStateException("duplicate")));
    }

    @Test
    void alreadySettledObserverRejectsForeignOverwriteWithoutChangingResult() {
        AtomicReference<OresFuture.Callback<Integer>> retained =
                new AtomicReference<>();
        OresFuture<Integer> observer = OresFuture.fromCallback(retained::set);
        OresFuture.Callback<Integer> foreign = retained.get();

        assertTrue(observer.completeFromRuntime(17));
        assertThrows(OresFuture.AlreadySettledException.class,
                () -> foreign.resolve(19));
        assertEquals(17, observer.join());
        assertTrue(foreign.isDone());
    }

    @Test
    void foreignRegistrarFailureBeforeCallbackBecomesRuntimeFailure() {
        IllegalStateException expected = new IllegalStateException("registrar failed");
        OresFuture<Integer> observer =
                OresFuture.fromCallback(callback -> { throw expected; });

        java.util.concurrent.CompletionException failure =
                assertThrows(java.util.concurrent.CompletionException.class,
                        observer::join);
        assertSame(expected, failure.getCause());
        assertFalse(observer.isCancelled());
    }
}
