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
