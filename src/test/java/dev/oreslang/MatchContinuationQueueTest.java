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
    void overCapacityFailsClosedWithoutExecutingPendingArms() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger executed = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.enqueue(() -> {
                    for (int i = 0; i <= MatchContinuationQueue.MAX_PENDING; i++) {
                        queue.enqueue(executed::incrementAndGet);
                    }
                }));
        assertTrue(failure.getMessage().contains("exceeds"), failure.toString());
        assertEquals(0, executed.get());
        assertTrue(queue.terminated());
        queue.enqueue(executed::incrementAndGet);
        assertEquals(0, executed.get());
    }

    @Test
    void concurrentlyEnqueuedCallbacksNeverExecuteInParallel() throws Exception {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> attempts = new ArrayList<>();
            for (int i = 0; i < 512; i++) {
                attempts.add(CompletableFuture.runAsync(() ->
                        queue.enqueue(() -> {
                            int currentlyActive = active.incrementAndGet();
                            maximum.accumulateAndGet(currentlyActive, Math::max);
                            Thread.yield();
                            completed.incrementAndGet();
                            active.decrementAndGet();
                        }), executor));
            }
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        }
        assertEquals(512, completed.get());
        assertEquals(1, maximum.get(), "two drainers must never execute guest work in parallel");
        assertFalse(queue.terminated());
    }

    @Test
    void nullWorkAndNullCompletionFailWithoutTerminatingTheQueue() {
        MatchContinuationQueue queue = new MatchContinuationQueue();
        assertThrows(NullPointerException.class, () -> queue.enqueue(null));
        assertThrows(NullPointerException.class, () -> queue.complete(null));
        assertFalse(queue.terminated());
        assertTrue(queue.complete(() -> { }));
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
