package dev.oreslang.nodes;

import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;

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
    @Test
    void abandonedPublishedFutureIsCancelledWhenScopeCloses() {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        OresFuture<Integer> orphan = new OresFuture<>();
        slot.claim("work", slot.nonce(), context, root);
        slot.publish("work", slot.nonce(), context, root, orphan);
        slot.close();
        assertTrue(orphan.isCancelled(),
                "scope teardown must not strand a published unreturned Future");
        assertThrows(IllegalStateException.class, slot::result);
    }

    @Test
    void foreignThreadCannotCloseAnotherHostsFutureScope() throws Exception {
        var slot = new OresHostFutureBridge.Slot("work");
        Object context = new Object();
        Object root = new Object();
        slot.claim("work", slot.nonce(), context, root);
        OresFuture<Integer> pending = new OresFuture<>();
        slot.publish("work", slot.nonce(), context, root, pending);
        java.util.concurrent.atomic.AtomicReference<Throwable> rejected =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                slot.close();
            } catch (Throwable failure) {
                rejected.set(failure);
            }
        });
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertInstanceOf(SecurityException.class, rejected.get(),
                "foreign worker may not release owner-scoped admission");
        assertFalse(pending.isDone(), "foreign close must not cancel the owner's work");
        assertSame(pending, slot.result());
        slot.close();
    }

}
