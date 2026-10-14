package dev.oreslang;

import dev.oreslang.runtime.MatchContinuationQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Low-level tests for the actual async match dispatcher queue.
 * Unlike guest-only smoke tests these directly inject abnormal callbacks.
 */
@Timeout(15)
final class MatchContinuationQueueTest {
    @Test
    void thousandsOfImmediatelyResolvedCallbacksAreStackSafe() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger callbacks = new AtomicInteger();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (callbacks.incrementAndGet() < 4096) queue.enqueue(step[0]);
            else queue.complete(() -> { });
        };
        assertDoesNotThrow(() -> queue.enqueue(step[0]));
        assertEquals(4096, callbacks.get());
        assertTrue(queue.terminated());
    }

    @Test
    void completingASelectionDiscardsQueuedAndLateCallbacks() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        List<String> events = new ArrayList<>();
        queue.enqueue(() -> {
            queue.enqueue(() -> events.add("wrong"));
            queue.complete(() -> events.add("winner"));
            queue.enqueue(() -> events.add("late"));
        });
        assertEquals(List.of("winner"), events);
        assertFalse(queue.complete(() -> events.add("duplicate")));
    }

    @Test
    void failedTerminalDeliveryCannotScheduleAnotherArm() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger invoked = new AtomicInteger();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> queue.enqueue(() -> {
                    queue.enqueue(invoked::incrementAndGet);
                    queue.complete(() -> { throw new IllegalStateException("terminal failure"); });
                }));
        assertEquals("terminal failure", error.getMessage());
        assertEquals(0, invoked.get());
        assertTrue(queue.terminated());
        queue.enqueue(invoked::incrementAndGet);
        assertEquals(0, invoked.get());
    }

    @Test
    void fatalHostErrorAbortsQueueWithoutInvokingLaterArms() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger invoked = new AtomicInteger();
        AssertionError fatal = assertThrows(AssertionError.class,
                () -> queue.enqueue(() -> {
                    queue.enqueue(invoked::incrementAndGet);
                    throw new AssertionError("carrier failure");
                }));
        assertEquals("carrier failure", fatal.getMessage());
        assertEquals(0, invoked.get());
        assertTrue(queue.terminated());
    }

    @Test
    void cancelSuppressesPendingWorkEvenIfADrainIsAlreadyRunning() throws Exception {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger late = new AtomicInteger();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> active = CompletableFuture.runAsync(() ->
                    queue.enqueue(() -> {
                        started.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("test drain did not release");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }), executor);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.enqueue(late::incrementAndGet);
            queue.abort();
            release.countDown();
            active.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertTrue(queue.terminated());
        assertEquals(0, late.get());
    }

    @Test
    void racingTerminalCallbacksCanDeliverOnlyOnce() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger deliveries = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                attempts.add(CompletableFuture.runAsync(
                        () -> queue.complete(deliveries::incrementAndGet), executor));
            }
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        }
        assertEquals(1, deliveries.get());
        assertTrue(queue.terminated());
    }
}
