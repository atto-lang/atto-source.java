package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Check the host-only prepare/notify split and its registration races. */
final class DeferredTerminalFutureNotificationTest {

    @Test
    void preRegisteredObserverIsDeferredWhileLateSubscriberSeesTerminalState()
            throws Exception {
        OresFuture<Integer> result = new OresFuture<>();
        AtomicInteger early = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();
        result.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            assertEquals(42, value);
            early.incrementAndGet();
        });

        Runnable publish = result.prepareCompleteFromRuntime(42);
        assertTrue(result.isDone(), "terminal outcome must be readable immediately");
        assertEquals(Integer.valueOf(42), result.get());
        assertEquals(0, early.get(), "pre-registered callback must wait for publication");

        // Terminal registration after preparation races with the eventual
        // bulk callback release, and must not be delivered twice.
        result.whenCompleteRuntime((value, failure) -> {
            assertNull(failure);
            assertEquals(42, value);
            late.incrementAndGet();
        });
        assertEquals(1, late.get());

        publish.run();
        publish.run(); // notification action is idempotent
        assertEquals(1, early.get());
        assertEquals(1, late.get());
        assertFalse(result.completeFromRuntime(99), "already settled result is immutable");
    }

    @Test
    void crossFutureWaitInTerminalCallbackNeverBlocksPublishingThread()
            throws Exception {
        OresFuture<Void> physical = new OresFuture<>();
        OresFuture<Void> done = new OresFuture<>();
        AtomicInteger observed = new AtomicInteger();
        physical.whenCompleteRuntime((ignored, failure) -> {
            assertNull(failure);
            assertTrue(done.isDone(),
                    "all host terminal states must settle before any callback runs");
            observed.incrementAndGet();
        });

        Runnable publishDone = done.prepareCompleteFromRuntime(null);
        Runnable publishPhysical = physical.prepareCompleteFromRuntime(null);
        publishPhysical.run();
        publishDone.run();

        assertEquals(1, observed.get());
    }

    @Test
    void deferredFailurePreservesOriginalThrowableIdentity() {
        OresFuture<Void> failed = new OresFuture<>();
        IllegalStateException original = new IllegalStateException("guest cleanup failure");
        AtomicInteger notifications = new AtomicInteger();
        failed.whenCompleteRuntime((value, cause) -> {
            assertNull(value);
            assertSame(original, cause);
            notifications.incrementAndGet();
        });

        Runnable publish = failed.prepareFailureFromRuntime(original);
        assertTrue(failed.isCompletedExceptionally());
        assertEquals(0, notifications.get());
        publish.run();
        publish.run();
        assertEquals(1, notifications.get());
        assertFalse(failed.failFromRuntime(new IllegalStateException("late")));
    }
}
