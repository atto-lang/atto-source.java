package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Shared OresFuture two-phase cancellation used by TaskScope and actor upgrade. */
@Timeout(15)
final class OresFutureDeferredCancellationActorScopeTest {
    @Test
    void siblingProducerRevocationsPrecedeEvenBlockingHostCallbacks()
            throws Exception {
        var first = new OresFuture<Integer>();
        var second = new OresFuture<Integer>();
        var seen = new AtomicInteger();
        var bothRevokedAtDelivery = new AtomicBoolean(true);
        first.whenCompleteRuntime((ignored, failure) -> {
            if (!second.isCancelled()) bothRevokedAtDelivery.set(false);
            if (failure instanceof CancellationException) seen.incrementAndGet();
        });
        second.whenCompleteRuntime((ignored, failure) -> {
            if (!first.isCancelled()) bothRevokedAtDelivery.set(false);
            if (failure instanceof CancellationException) seen.incrementAndGet();
        });
        Runnable firstDelivery = first.cancelDeferredNotification();
        assertNotNull(firstDelivery);
        assertTrue(first.isCancelled());
        assertEquals(0, seen.get(), "subscribers must not run during revocation");
        Runnable secondDelivery = second.cancelDeferredNotification();
        assertNotNull(secondDelivery);
        assertTrue(second.isCancelled());

        // A late subscriber is queued until the owner opens its delivery
        // barrier, rather than running on the registering host thread.
        first.whenCompleteRuntime((ignored, failure) -> {
            if (!second.isCancelled()) bothRevokedAtDelivery.set(false);
            seen.incrementAndGet();
        });
        assertEquals(0, seen.get());
        firstDelivery.run();
        secondDelivery.run();
        firstDelivery.run();
        secondDelivery.run();
        assertEquals(3, seen.get(), "single-use delivery must not replay");
        assertTrue(bothRevokedAtDelivery.get());
    }

    @Test
    void racingDeliveryClosuresStillRunSubscribersAtMostOnce() throws Exception {
        var future = new OresFuture<Integer>();
        var delivered = new AtomicInteger();
        future.whenCompleteRuntime((ignored, failure) ->
                delivered.incrementAndGet());
        Runnable notification = future.cancelDeferredNotification();
        assertNotNull(notification);
        var go = new CountDownLatch(1);
        var workers = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 12; i++) {
            Thread contender = Thread.ofPlatform().start(() -> {
                try {
                    if (!go.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("delivery start timeout");
                    notification.run();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(contender);
        }
        go.countDown();
        for (Thread contender : workers) {
            contender.join(5000);
            assertFalse(contender.isAlive());
        }
        assertEquals(1, delivered.get());
        assertNull(future.cancelDeferredNotification());
    }

    @Test
    void completedResultCannotBeCancelledByLaterScopeShutdown() throws Exception {
        var future = new OresFuture<Integer>();
        assertTrue(future.completeFromRuntime(17));
        assertNull(future.cancelDeferredNotification());
        assertFalse(future.isCancelled());
        assertEquals(17, future.get(5, TimeUnit.SECONDS));
    }
}
