package dev.oreslang.nodes;

import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic one-use admission checks, independent of Truffle scheduling. */
final class OresHostFutureBridgeTicketTest {
    @Test
    void claimIsSingleUseEvenBeforeProducerPublishes() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        slot.claim("work", slot.nonce(), context, root);
        assertThrows(SecurityException.class,
                () -> slot.claim("work", slot.nonce(), context, root),
                "reentrant command must not dispatch a second task");
        OresFuture<Integer> future = new OresFuture<>();
        slot.publish("work", slot.nonce(), context, root, future);
        assertSame(future, slot.result());
        assertThrows(IllegalStateException.class, slot::result,
                "one-use handoff must not return the same Future twice");
        assertFalse(future.isCancelled(), "delivery must not cancel the caller-owned Future");
        slot.cancelUnreturnedOnHostFailure(new IllegalStateException("after delivery"));
        assertFalse(future.isCancelled(),
                "failure handling after a completed handoff must not cancel caller-owned work");
        assertThrows(SecurityException.class,
                () -> slot.publish("work", slot.nonce(), context, root, new OresFuture<>()));
        slot.close();
        assertThrows(SecurityException.class,
                () -> slot.claim("work", slot.nonce(), context, root));
        assertThrows(IllegalStateException.class, slot::result);
    }

    @Test
    void wrongFunctionOrNonceCannotConsumeTicket() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        assertThrows(SecurityException.class,
                () -> slot.claim("other", slot.nonce(), context, root));
        assertThrows(SecurityException.class,
                () -> slot.claim("work", "forged-or-stale", context, root));
        slot.claim("work", slot.nonce(), context, root);
        assertThrows(SecurityException.class,
                () -> slot.claim("work", slot.nonce(), context, root));
        slot.close();
    }

    @Test
    void crossContextOrSourceRootCannotPublishCapturedFuture() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        slot.claim("work", slot.nonce(), context, root);
        assertThrows(SecurityException.class,
                () -> slot.publish("work", slot.nonce(), new Object(), root, new OresFuture<>()));
        assertThrows(SecurityException.class,
                () -> slot.publish("work", slot.nonce(), context, new Object(), new OresFuture<>()));
        assertThrows(SecurityException.class,
                () -> slot.publish("other", slot.nonce(), context, root, new OresFuture<>()));
        assertThrows(IllegalStateException.class, slot::result);
        OresFuture<Integer> original = new OresFuture<>();
        slot.publish("work", slot.nonce(), context, root, original);
        assertSame(original, slot.result());
        slot.close();
    }

    @Test
    void rejectedClaimCannotRetainPartiallyValidatedContext() throws Exception {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Field contextOwner = OresHostFutureBridge.Slot.class.getDeclaredField("ownerContext");
        contextOwner.setAccessible(true);
        assertThrows(NullPointerException.class,
                () -> slot.claim("work", slot.nonce(), context, null));
        assertNull(contextOwner.get(slot),
                "partial claim must not keep an unclaimed context alive");
        Object root = new Object();
        slot.claim("work", slot.nonce(), context, root);
        assertSame(context, contextOwner.get(slot));
        slot.close();
        assertNull(contextOwner.get(slot));
    }

    @Test
    void failedOuterHostReturnCancelsPublishedProducer() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        OresFuture<Integer> pending = new OresFuture<>();
        slot.claim("work", slot.nonce(), context, root);
        slot.publish("work", slot.nonce(), context, root, pending);
        RuntimeException hostFailure = new IllegalStateException("outer Polyglot return failed");
        slot.cancelUnreturnedOnHostFailure(hostFailure);
        assertTrue(pending.isCancelled(),
                "a published producer without a host receiver must be cancelled");
        assertEquals(0, hostFailure.getSuppressed().length);
        slot.close();
        assertThrows(IllegalStateException.class, slot::result);
    }

    @Test
    void hostReturnFailureBeforePublicationDoesNotMutateOtherFutures() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        slot.claim("work", slot.nonce(), context, root);
        slot.cancelUnreturnedOnHostFailure(new IllegalStateException("unpublished"));
        OresFuture<Integer> independent = new OresFuture<>();
        assertFalse(independent.isDone());
        slot.close();
    }

    @Test
    void ticketCannotBeUsedOrClosedFromAnotherPhysicalThread() throws Exception {
        var slot = new OresHostFutureBridge.Slot("work");
        String nonce = slot.nonce();
        Object context = new Object();
        Object root = new Object();
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                assertThrows(SecurityException.class, slot::nonce);
                assertThrows(SecurityException.class,
                        () -> slot.claim("work", nonce, context, root));
                assertThrows(SecurityException.class,
                        () -> slot.publish("work", nonce, context, root, new OresFuture<>()));
                assertThrows(SecurityException.class, slot::result);
                assertThrows(SecurityException.class,
                        () -> slot.cancelUnreturnedOnHostFailure(new IllegalStateException("foreign")));
                assertThrows(SecurityException.class, slot::close);
            }).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        // A rejected foreign thread must not consume or close the real claim.
        slot.claim("work", nonce, context, root);
        OresFuture<Integer> future = new OresFuture<>();
        slot.publish("work", nonce, context, root, future);
        assertSame(future, slot.result());
        assertThrows(IllegalStateException.class, slot::result);
        slot.close();
    }

    @Test
    void stagedTaskLimitRejectsBeforeUnownedSchedulerRegistration() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var slot = new OresHostFutureBridge.Slot("work");
            Object context = new Object();
            slot.claim("work", slot.nonce(), context, new Object());
            var bodies = new AtomicInteger();
            var prepared = new ArrayList<OresFuture<?>>();
            for (int i = 0; i < OresHostFutureBridge.Slot.MAX_DEFERRED_SOURCE_STARTS; ++i) {
                slot.requireCapacityForStaging();
                var deferred = scheduler.prepareDeferred(resume -> {
                    bodies.incrementAndGet();
                    return OresScheduler.done(1);
                });
                slot.stageSourceStart(deferred);
                prepared.add(deferred.future());
            }
            assertThrows(RejectedExecutionException.class, slot::requireCapacityForStaging,
                    "overflow must be rejected before adding an unowned scheduler task");
            // Closing the scope must drain ALL already-staged NEW tasks, with
            // no physical guest entry or orphaned Future.
            slot.close();
            for (OresFuture<?> future : prepared) {
                assertTrue(future.isDone(), "abandoned source task was not settled");
                assertTrue(future.isCancelled(), "abandoned source task was resurrected");
            }
            assertEquals(0, bodies.get());
            assertThrows(SecurityException.class, slot::requireCapacityForStaging);
        }
    }

    @Test
    void failedHostHandoffDrainsPreparedSourceTasksBeforeScopeClose() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var slot = new OresHostFutureBridge.Slot("work");
            Object context = new Object();
            Object root = new Object();
            slot.claim("work", slot.nonce(), context, root);
            var bodies = new AtomicInteger();
            var prepared = new ArrayList<OresFuture<?>>();
            for (int i = 0; i < 3; ++i) {
                slot.requireCapacityForStaging();
                var deferred = scheduler.prepareDeferred(resume -> {
                    bodies.incrementAndGet();
                    return OresScheduler.done(10);
                });
                slot.stageSourceStart(deferred);
                prepared.add(deferred.future());
            }
            IllegalStateException failure = new IllegalStateException("host return rejected");
            slot.cancelUnreturnedOnHostFailure(failure);
            assertEquals(0, failure.getSuppressed().length);
            for (OresFuture<?> future : prepared) {
                assertTrue(future.isDone());
                assertTrue(future.isCancelled());
            }
            assertEquals(0, bodies.get());
            slot.close();
            assertEquals(0, bodies.get());
        }
    }

    @Test
    void absentContextIdentityFailsBeforeClaim() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object root = new Object();
        assertThrows(NullPointerException.class,
                () -> slot.claim("work", slot.nonce(), null, root));
        // A failed identity validation may not leave a partially claimed ticket.
        Object context = new Object();
        slot.claim("work", slot.nonce(), context, root);
        assertThrows(IllegalStateException.class, slot::result);
        slot.close();
    }
}
