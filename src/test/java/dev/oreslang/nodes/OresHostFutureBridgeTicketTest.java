package dev.oreslang.nodes;

import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;

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
