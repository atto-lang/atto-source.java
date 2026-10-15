package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
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

    @Test void missedReleaseRetriesTheNewSupervisorRatherThanAnOlderWaiter() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(3);
        StringBuilder order = new StringBuilder();
        long epoch = queue.epoch();
        assertTrue(queue.register(() -> order.append('A'), epoch, 0));
        assertTrue(queue.register(() -> order.append('B'), epoch, 0));
        // One slot release wakes A while B remains registered.
        queue.slotReleased(0);
        assertEquals("A", order.toString());
        assertEquals(1, queue.pending());
        // The new parent observed the old epoch before attempting to
        // schedule. It must retry itself immediately, not consume B's slot.
        assertTrue(queue.register(() -> order.append('C'), epoch, 0));
        assertEquals("AC", order.toString());
        assertEquals(1, queue.pending(), "older B must retain its registration");
        queue.slotReleased(0);
        assertEquals("ACB", order.toString());
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

    @Test void continuouslyMatchingAffinityCannotStarveOldestSupervisor() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(64);
        AtomicInteger oldest = new AtomicInteger();
        AtomicInteger localityMatches = new AtomicInteger();
        long epoch = queue.epoch();
        assertTrue(queue.register(oldest::incrementAndGet, epoch, 0));
        for (int i = 0; i < 32; ++i) {
            assertTrue(queue.register(localityMatches::incrementAndGet, epoch, 1));
        }

        for (int i = 0; i < 16; ++i) queue.slotReleased(1);
        assertEquals(0, oldest.get(), "affinity may prefer 16 matching wakes");
        assertEquals(16, localityMatches.get());
        queue.slotReleased(1);
        assertEquals(1, oldest.get(),
                "older supervisor must run after bounded affinity bypasses");
        assertEquals(16, localityMatches.get());
        for (int i = 0; i < 16; ++i) queue.slotReleased(1);
        assertEquals(32, localityMatches.get());
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

    @Test void hostRetryFailureCounterSaturatesAndClampsHistoricalOverflow()
            throws Exception {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(2);
        // Advance the private telemetry state without billions of synthetic
        // submissions; this is a host-owned field and not guest-visible.
        java.lang.reflect.Field failures =
                ControlWakeRetryQueue.class.getDeclaredField("callbacksFailed");
        failures.setAccessible(true);
        failures.setLong(queue, Long.MAX_VALUE - 1);

        Runnable throwAssertion = () -> { throw new AssertionError("host retry bug"); };
        Runnable throwRuntime = () -> { throw new IllegalStateException("retry denied"); };
        assertTrue(queue.register(throwAssertion, queue.epoch()));
        queue.slotReleased();
        assertEquals(Long.MAX_VALUE, queue.callbacksFailed());
        assertTrue(queue.register(throwRuntime, queue.epoch()));
        queue.slotReleased();
        assertEquals(Long.MAX_VALUE, queue.callbacksFailed(),
                "host CONTROL retry diagnostics must never wrap negative");

        failures.setLong(queue, Long.MIN_VALUE);
        assertTrue(queue.register(throwRuntime, queue.epoch()));
        queue.slotReleased();
        assertEquals(Long.MAX_VALUE, queue.callbacksFailed(),
                "existing negative telemetry must be clamped, not propagated");
        assertEquals(0, queue.pending());
    }

    @Test void concurrentFailedWakeCallbacksSaturateWithoutQueueLeaks()
            throws Exception {
        final int workers = 8;
        final int attempts = 1024;
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(1);
        queue.slotReleased();
        long staleEpoch = queue.epoch() - 1;
        java.lang.reflect.Field failures =
                ControlWakeRetryQueue.class.getDeclaredField("callbacksFailed");
        failures.setAccessible(true);
        failures.setLong(queue, Long.MAX_VALUE - 17);

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workers);
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> jobs =
                    new java.util.ArrayList<>();
            for (int t = 0; t < workers; t++) {
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < attempts; i++) {
                        assertTrue(queue.register(
                                () -> { throw new IllegalStateException("retry rejection"); },
                                staleEpoch),
                                "a missed-epoch retry must not consume queue capacity");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, queue.callbacksFailed(),
                    "concurrent failed retry callbacks may not wrap telemetry");
            assertEquals(0, queue.pending(),
                    "an immediately retried callback cannot strand a queue slot");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test void fatalVmErrorIsNeverSwallowedByControlRetryIsolation() {
        ControlWakeRetryQueue queue = new ControlWakeRetryQueue(1);
        assertTrue(queue.register(() -> {
            throw new InternalError("fatal host VM condition");
        }, queue.epoch()));
        assertThrows(InternalError.class, queue::slotReleased);
        assertEquals(0, queue.callbacksFailed(),
                "fatal VM errors must not be converted to ordinary retry failures");
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
