package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Linearization proof candidates for the scheduler-only deferred cancellation
 * state, independently of ActorRuntime and SourceTask compiler lowering.
 */
@Timeout(25)
final class DeferredFutureCancellationRaceTest {
    @Test void cancellationRequestIsPendingUntilPhysicalCleanupCompletes()
            throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(
                () -> true, hooks::incrementAndGet, () -> { }, true);
        assertTrue(future.cancel(false));
        assertEquals(1, hooks.get());
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());
        assertFalse(future.completeFromRuntime(42));
        assertFalse(future.failFromRuntime(new IllegalStateException("late error")));
        assertFalse(future.cancel(false));
        assertTrue(future.completeDeferredCancellationFromRuntime());
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, future::join);
        assertFalse(future.completeDeferredCancellationFromRuntime());
    }

    @Test void successfulCompletionBeforeCancelNeverCreatesStaleCancelRequest()
            throws Exception {
        OresFuture<Integer> future = new OresFuture<>(
                () -> true, () -> fail("terminal success forbids revocation"),
                () -> { }, true);
        assertTrue(future.completeFromRuntime(11));
        assertFalse(future.cancel(false));
        assertNull(future.deferredCancellationCause());
        assertEquals(Integer.valueOf(11), future.get(2, TimeUnit.SECONDS));
    }

    @Test void concurrentCompletionVersusCancellationHasExactlyOneWinner()
            throws Exception {
        for (int iteration = 0; iteration < 120; iteration++) {
            OresFuture<Integer> future =
                    new OresFuture<>(() -> true, () -> { }, () -> { }, true);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean completed = new AtomicBoolean();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread producer = Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    completed.set(future.completeFromRuntime(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread revoker = Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    cancelled.set(future.cancel(false));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            go.countDown();
            producer.join(2000);
            revoker.join(2000);
            assertFalse(producer.isAlive());
            assertFalse(revoker.isAlive());
            assertNotEquals(completed.get(), cancelled.get(),
                    "exactly one terminal-admission decision must win");
            if (cancelled.get()) {
                assertFalse(future.isDone());
                assertTrue(future.completeDeferredCancellationFromRuntime());
                assertTrue(future.isCancelled());
                assertThrows(CancellationException.class, future::join);
            } else {
                assertNull(future.deferredCancellationCause());
                assertEquals(Integer.valueOf(2),
                        future.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void physicalCleanupFailureCannotBeRewrittenAsCancellation()
            throws Exception {
        OresFuture<Integer> future = new OresFuture<>(
                () -> true, () -> { }, () -> { }, true);
        IllegalStateException cleanupFailure =
                new IllegalStateException("unsafe lock release");
        assertTrue(future.cancel(false));
        assertTrue(future.failDeferredCleanupFromRuntime(cleanupFailure));
        assertSame(cleanupFailure, assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS)).getCause());
        assertFalse(future.isCancelled());
        assertFalse(future.completeDeferredCancellationFromRuntime());
    }
}
