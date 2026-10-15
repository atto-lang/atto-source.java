package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Deterministic lost-wakeup and conservation checks for the host CONTROL
 * retry queue. No wall clock, background worker or real actor heap needed.
 */
final class ControlWakeRetryQueueTest {
    @Test void releaseBetweenRejectedSubmissionAndRegistrationRetriesImmediately() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        AtomicInteger called = new AtomicInteger();
        long before = queue.epoch();
        queue.slotReleased();
        assertTrue(queue.register(called::incrementAndGet, before));
        assertEquals(1, called.get());
        assertEquals(0, queue.pending());
    }

    @Test void registeredWakeRunsOnlyAfterOneSlotIsFreed() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        AtomicInteger called = new AtomicInteger();
        assertTrue(queue.register(called::incrementAndGet, queue.epoch()));
        assertEquals(0, called.get());
        assertEquals(1, queue.pending());
        queue.slotReleased();
        assertEquals(1, called.get());
        queue.slotReleased();
        assertEquals(1, called.get());
    }

    @Test void backlogRemainsBoundedAndServesRegistrantsInOrder() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        StringBuilder order = new StringBuilder();
        long epoch = queue.epoch();
        assertTrue(queue.register(() -> order.append('A'), epoch));
        assertTrue(queue.register(() -> order.append('B'), epoch));
        assertFalse(queue.register(() -> order.append('C'), epoch));
        assertEquals(2, queue.pending());
        queue.slotReleased();
        assertEquals("A", order.toString());
        queue.slotReleased();
        assertEquals("AB", order.toString());
        assertEquals(0, queue.pending());
    }

    @Test void affinityReleasePrefersMatchingLaneWithinBoundedWindow() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(4);
        StringBuilder order = new StringBuilder();
        long epoch = queue.epoch();
        assertTrue(queue.register(() -> order.append('A'), epoch, 0));
        assertTrue(queue.register(() -> order.append('B'), epoch, 1));
        assertTrue(queue.register(() -> order.append('C'), epoch, 0));
        queue.slotReleased(1);
        assertEquals("B", order.toString(),
                "lane one should not retry a saturated unrelated lane first");
        queue.slotReleased(0);
        queue.slotReleased(0);
        assertEquals("BAC", order.toString());
        assertEquals(0, queue.pending());
    }

    @Test void globalAdmissionReleaseFallsBackToOtherLaneWithoutStarvation() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        AtomicInteger executed = new AtomicInteger();
        assertTrue(queue.register(executed::incrementAndGet, queue.epoch(), 3));
        // The permit may have been held by an unrelated lane. A purely
        // lane-local queue would strand this supervisor indefinitely.
        queue.slotReleased(9);
        assertEquals(1, executed.get());
        assertEquals(0, queue.pending());
    }

    @Test void boundedLocalityScanStillMakesProgressOnLargeBacklogs() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(32);
        StringBuilder delivery = new StringBuilder();
        long epoch = queue.epoch();
        for (int i = 0; i < 20; ++i) {
            assertTrue(queue.register(() -> delivery.append('x'), epoch, 0));
        }
        assertTrue(queue.register(() -> delivery.append('y'), epoch, 1));
        queue.slotReleased(1);
        // The scan is bounded; outside that window, oldest waiter wins.
        assertEquals("x", delivery.toString());
        for (int i = 0; i < 19; ++i) queue.slotReleased(0);
        queue.slotReleased(1);
        assertEquals(21, delivery.length());
        assertEquals('y', delivery.charAt(20));
        assertEquals(0, queue.pending());
    }

    @Test void cancelledOrClosedSupervisorNeverReceivesStaleWake() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        AtomicInteger called = new AtomicInteger();
        Runnable callback = called::incrementAndGet;
        assertTrue(queue.register(callback, queue.epoch()));
        queue.cancel(callback);
        queue.slotReleased();
        assertEquals(0, called.get());
        assertEquals(0, queue.pending());
        assertTrue(queue.register(callback, queue.epoch()));
        queue.close();
        queue.slotReleased();
        assertEquals(0, called.get());
        assertFalse(queue.register(callback, queue.epoch()));
        assertEquals(0, queue.pending());
    }

    @Test void concurrentRegistrationsCancellationsAndReleasesRemainBounded()
            throws Exception {
        final int total = 4096;
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(total);
        java.util.concurrent.atomic.AtomicIntegerArray deliveries =
                new java.util.concurrent.atomic.AtomicIntegerArray(total);
        java.util.concurrent.atomic.AtomicInteger cancellations =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<?>> tasks =
                    new java.util.ArrayList<>();
            for (int producer = 0; producer < 4; ++producer) {
                final int stride = producer;
                tasks.add(workers.submit(() -> {
                    start.await();
                    for (int i = stride; i < total; i += 4) {
                        final int index = i;
                        Runnable callback = () -> deliveries.incrementAndGet(index);
                        long epoch = queue.epoch();
                        if (!queue.register(callback, epoch, index % 7)) {
                            throw new AssertionError("unexpected retry queue exhaustion");
                        }
                        if (index % 13 == 0) {
                            queue.cancel(callback);
                            cancellations.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (int consumer = 0; consumer < 4; ++consumer) {
                final int lane = consumer;
                tasks.add(workers.submit(() -> {
                    start.await();
                    for (int i = 0; i < total; ++i) {
                        queue.slotReleased((lane + i) % 7);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            // Any registrations after the concurrent releases must still be
            // drained by later real queue-slot-release events.
            for (int i = 0; i < total; ++i) queue.slotReleased(i % 7);
            int observed = 0;
            for (int i = 0; i < total; ++i) {
                int count = deliveries.get(i);
                assertTrue(count <= 1, "CONTROL callback ran twice for " + i);
                observed += count;
            }
            assertEquals(0, queue.pending());
            assertTrue(observed >= total - cancellations.get(),
                    "a noncancelled registered callback was lost");
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test void assertionInHostRetryCannotAbortAdmittedActorTurn() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(1);
        java.util.concurrent.atomic.AtomicInteger actorRan =
                new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(queue.register(() -> {
            throw new AssertionError("synthetic host retry bug");
        }, queue.epoch()));
        // QueuedTurn.run() releases a physical slot before calling
        // the already-admitted actor turn; the wake must not abort it.
        queue.slotReleased();
        actorRan.incrementAndGet();
        assertEquals(1, actorRan.get());
        assertEquals(1, queue.callbacksFailed());
        assertEquals(0, queue.pending());
    }

    @Test void failedRetryCallbackDoesNotPreventCurrentCarrierFromRunning() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(1);
        AtomicInteger second = new AtomicInteger();
        assertTrue(queue.register(
                () -> { throw new IllegalStateException("injected scheduler rejection"); },
                queue.epoch()));
        queue.slotReleased();
        assertEquals(1, queue.callbacksFailed());
        assertTrue(queue.register(second::incrementAndGet, queue.epoch()));
        queue.slotReleased();
        assertEquals(1, second.get());
    }
}
