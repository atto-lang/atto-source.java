package dev.oreslang.nodes;

import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
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
