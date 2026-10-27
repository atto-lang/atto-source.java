package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused host tests, not guest source or GraalVM Native Image conformance. */
public class OresFutureObserverTest {
    @Test
    void observationIsDifferentObjectAndCancellingItCannotCancelTheProducer() {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> view = OresFuture.observe(source);
        assertNotSame(source, view);
        assertEquals(1, source.pendingRuntimeWaiterCount());
        assertTrue(view.cancel(false));
        assertTrue(view.isCancelled());
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertFalse(source.isDone());
        assertTrue(source.completeFromRuntime(42));
        assertEquals(42, source.join());
    }

    @Test
    void pendingAndAlreadyTerminalSuccessUseExactlyOneResult() {
        OresFuture<String> source = new OresFuture<>();
        OresFuture<String> view = OresFuture.observe(source);
        assertTrue(source.completeFromRuntime("ok"));
        assertEquals("ok", view.join());
        assertEquals(0, source.pendingRuntimeWaiterCount());
        OresFuture<String> late = OresFuture.observe(source);
        assertNotSame(source, late);
        assertTrue(late.isDone());
        assertEquals("ok", late.join());
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void genuineCancellationAndUserThrownCancellationAreNotConflated() {
        OresFuture<Integer> genuine = new OresFuture<>();
        OresFuture<Integer> cancelled = OresFuture.observe(genuine);
        assertTrue(genuine.cancel(false));
        assertTrue(cancelled.isCancelled());
        assertEquals(0, genuine.pendingRuntimeWaiterCount());

        OresFuture<Integer> failed = new OresFuture<>();
        OresFuture<Integer> failureView = OresFuture.observe(failed);
        CancellationException thrownByGuest = new CancellationException("user");
        assertTrue(failed.failFromRuntime(thrownByGuest));
        assertFalse(failed.isCancelled());
        assertFalse(failureView.isCancelled());
        assertTrue(failureView.isCompletedExceptionally());
        assertSame(thrownByGuest,
                assertThrows(CompletionException.class, failureView::join).getCause());
    }

    @Test
    void nullValueAndFailurePreserveObservationSemantics() {
        OresFuture<Void> voidSource = OresFuture.completed(null);
        OresFuture<Void> voidView = OresFuture.observe(voidSource);
        assertTrue(voidView.isDone());
        assertNull(voidView.join());

        OresFuture<Integer> failed = new OresFuture<>();
        IllegalArgumentException failure = new IllegalArgumentException("expected");
        OresFuture<Integer> view = OresFuture.observe(failed);
        assertTrue(failed.failFromRuntime(failure));
        assertSame(failure,
                assertThrows(CompletionException.class, view::join).getCause());
    }

    @Test
    void independentCancellationAndProducerSettlementRaceCannotRetainWaiter()
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 128; i++) {
                OresFuture<Integer> source = new OresFuture<>();
                OresFuture<Integer> observer = OresFuture.observe(source);
                CountDownLatch start = new CountDownLatch(1);
                var cancelled = pool.submit(() -> {
                    start.await();
                    observer.cancel(false);
                    return null;
                });
                var produced = pool.submit(() -> {
                    start.await();
                    source.completeFromRuntime(42);
                    return null;
                });
                start.countDown();
                cancelled.get(3, TimeUnit.SECONDS);
                produced.get(3, TimeUnit.SECONDS);
                assertTrue(observer.isDone());
                assertFalse(source.isCancelled());
                assertEquals(42, source.join());
                assertEquals(0, source.pendingRuntimeWaiterCount());
            }
        }
    }

    @Test
    void longLivedSourceDoesNotRetainCancelledObservationWaiters() {
        OresFuture<Integer> source = new OresFuture<>();
        AtomicInteger cancelled = new AtomicInteger();
        for (int i = 0; i < 256; i++) {
            OresFuture<Integer> view = OresFuture.observe(source);
            assertEquals(1, source.pendingRuntimeWaiterCount());
            if (view.cancel(false)) cancelled.incrementAndGet();
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
        assertEquals(256, cancelled.get());
        assertFalse(source.isDone());
        source.completeFromRuntime(42);
    }
}
