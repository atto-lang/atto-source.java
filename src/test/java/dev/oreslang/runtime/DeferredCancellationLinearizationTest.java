package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancellation request and producer completion share one linearization point
 * for cleanup-aware source Futures. No external observer can see success after
 * an accepted cancellation, and a timed-out host waiter cannot pin the task.
 */
@Timeout(30)
final class DeferredCancellationLinearizationTest {
    @Test
    void acceptedCancellationDoesNotSettleBeforeLexicalCleanup() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OresFuture<Integer> future = new OresFuture<>(requests::incrementAndGet, true);
        assertTrue(future.cancel(false));
        assertEquals(1, requests.get());
        assertFalse(future.isDone(), "request and publication are different events");
        assertFalse(future.isCancelled());
        assertFalse(future.cancel(false), "one cleanup request per task");
        assertTrue(future.completeDeferredCancellationFromRuntime());
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertEquals(1, requests.get());
    }

    @Test
    void producerSuccessAfterAcceptedCancellationCannotOverwriteIt() throws Exception {
        OresFuture<Integer> future = new OresFuture<>(() -> {}, true);
        assertTrue(future.cancel(false));
        assertTrue(future.completeFromRuntime(17));
        assertTrue(future.isCancelled());
        assertThrows(CancellationException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertFalse(future.completeDeferredCancellationFromRuntime());
    }

    @Test
    void hostFatalErrorOutranksAcceptedCancellation() throws Exception {
        OresFuture<Integer> future = new OresFuture<>(() -> {}, true);
        LinkageError fatal = new LinkageError("host fatal");
        assertTrue(future.cancel(false));
        assertTrue(future.failFromRuntime(fatal));
        ExecutionException observed = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertSame(fatal, observed.getCause());
    }

    @Test
    void ordinaryFailureAfterCancellationIsSuppressedOnOriginalCause() throws Exception {
        OresFuture<Integer> future = new OresFuture<>(() -> {}, true);
        assertTrue(future.cancel(false));
        CancellationException requested = future.deferredCancellationCause();
        IllegalArgumentException cleanup = new IllegalArgumentException("defer failed");
        assertTrue(future.failFromRuntime(cleanup));
        assertTrue(future.isCancelled());
        assertSame(requested, assertThrows(CancellationException.class,
                () -> future.get(1, TimeUnit.SECONDS)));
        assertArrayEquals(new Throwable[] {cleanup}, requested.getSuppressed());
    }

    @Test
    void completionCancellationRaceHasOnlyOneAuthoritativeResult() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            OresFuture<Integer> future = new OresFuture<>(() -> {}, true);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicBoolean cancelWon = new AtomicBoolean();
            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    gate.await();
                    future.completeFromRuntime(123);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    gate.await();
                    cancelWon.set(future.cancel(false));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            producer.join();
            canceller.join();
            assertTrue(future.isDone());
            if (cancelWon.get()) {
                assertTrue(future.isCancelled(),
                        "accepted cancellation must defeat an overlapping success");
                assertThrows(CancellationException.class,
                        () -> future.get(1, TimeUnit.SECONDS));
            } else {
                assertFalse(future.isCancelled());
                assertEquals(123, future.get(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void timedOutHostGetDetachesItsWaiterWithoutCancellingProducer() {
        OresFuture<Integer> producer = new OresFuture<>();
        assertThrows(TimeoutException.class,
                () -> producer.get(1, TimeUnit.MILLISECONDS));
        assertEquals(0, producer.pendingRuntimeWaiterCount(),
                "timed-out host waiter cannot retain a stopped actor or producer");
        assertFalse(producer.isDone());
        assertTrue(producer.completeFromRuntime(5));
    }
}
