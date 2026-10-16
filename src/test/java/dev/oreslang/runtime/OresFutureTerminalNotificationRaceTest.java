package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Standalone terminal-callback regression checks on current main.
 *
 * <p>Cancellation is observable at its CAS, but runtime notification must wait
 * for revocation; terminal callbacks must not strand sibling registrations;
 * and notification admission must remain held until callbacks actually return.
 */
@Timeout(20)
final class OresFutureTerminalNotificationRaceTest {
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new AssertionError("Future terminal barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Future terminal barrier interrupted", interrupted);
        }
    }

    @Test
    void lateRegistrationCannotBypassCancellationRevocation() throws Exception {
        var revoking = new CountDownLatch(1);
        var releaseRevocation = new CountDownLatch(1);
        var hookFinished = new AtomicInteger();
        var notifications = new AtomicInteger();
        var drained = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var producer = new OresFuture<Integer>(() -> {
            revoking.countDown();
            await(releaseRevocation);
            hookFinished.incrementAndGet();
        }, drained::incrementAndGet);
        producer.whenCompleteRuntime((value, error) -> {
            if (error instanceof java.util.concurrent.CancellationException
                    && hookFinished.get() == 1) notifications.incrementAndGet();
        });
        Thread canceller = Thread.startVirtualThread(() -> {
            try {
                assertTrue(producer.cancel(false));
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        try {
            await(revoking);
            assertTrue(producer.isCancelled());
            assertNull(producer.runtimeTerminalStateOrNull(),
                    "scheduler cannot observe cancellation before revocation");
            producer.whenCompleteRuntime((value, error) -> {
                if (error instanceof java.util.concurrent.CancellationException
                        && hookFinished.get() == 1) notifications.incrementAndGet();
            });
            assertEquals(0, notifications.get(), "late listener ran inside revocation");
            assertEquals(0, drained.get(), "notification slot retired too early");
            assertEquals(2, producer.pendingRuntimeWaiterCount());
        } finally {
            releaseRevocation.countDown();
            canceller.join(10_000);
        }
        assertFalse(canceller.isAlive());
        assertNull(failure.get(), "cancellation thread failed");
        assertEquals(2, notifications.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(1, drained.get());
    }

    @Test
    void throwingListenerDoesNotStrandSiblingOrNotificationDrain() {
        var delivered = new AtomicInteger();
        var drained = new AtomicInteger();
        var producer = new OresFuture<Integer>(() -> { }, drained::incrementAndGet);
        producer.whenCompleteRuntime((value, failure) -> {
            throw new IllegalStateException("subscriber failed");
        });
        producer.whenCompleteRuntime((value, failure) -> {
            if (value != null && value == 73 && failure == null) {
                delivered.incrementAndGet();
            }
        });
        assertTrue(producer.completeFromRuntime(73));
        assertEquals(1, delivered.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(1, drained.get());
        assertEquals(73, producer.join());
    }

    @Test
    void throwingCancellationHookStillNotifiesAllSubscribers() {
        var delivered = new AtomicInteger();
        var drained = new AtomicInteger();
        var producer = new OresFuture<Integer>(
                () -> { throw new IllegalStateException("revoker failed"); },
                drained::incrementAndGet);
        producer.whenCompleteRuntime((value, failure) -> {
            if (failure instanceof java.util.concurrent.CancellationException) {
                delivered.incrementAndGet();
            }
        });
        producer.whenCompleteRuntime((value, failure) -> {
            if (failure instanceof java.util.concurrent.CancellationException) {
                delivered.incrementAndGet();
            }
        });
        assertTrue(producer.cancel(false));
        assertTrue(producer.isCancelled());
        assertEquals(2, delivered.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
        assertEquals(1, drained.get());
    }

    @Test
    void notificationPermitStaysHeldUntilBlockingListenerActuallyReturns()
            throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var drained = new AtomicInteger();
        var second = new AtomicInteger();
        var background = new AtomicReference<Throwable>();
        var producer = new OresFuture<Integer>(() -> { }, drained::incrementAndGet);
        producer.whenCompleteRuntime((value, failure) -> {
            inside.countDown();
            await(release);
        });
        producer.whenCompleteRuntime((value, failure) -> second.incrementAndGet());
        Thread completer = Thread.startVirtualThread(() -> {
            try {
                assertTrue(producer.completeFromRuntime(17));
            } catch (Throwable thrown) {
                background.set(thrown);
            }
        });
        try {
            await(inside);
            assertTrue(producer.isDone(), "terminal CAS should already be visible");
            assertEquals(0, drained.get(), "notification permit retired inside callback");
            assertEquals(0, second.get(), "later subscriber overtook blocked first");
        } finally {
            release.countDown();
            completer.join(10_000);
        }
        assertFalse(completer.isAlive());
        assertNull(background.get());
        assertEquals(1, second.get());
        assertEquals(1, drained.get());
        assertEquals(0, producer.pendingRuntimeWaiterCount());
    }
}
