package dev.oreslang;

import dev.oreslang.runtime.VirtualActorActivationRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VirtualActorActivationRegistryTest {
    @Test
    void sameLogicalKeyResolvesOneIncarnationAndPublicationIsTwoPhase() {
        var registry = new VirtualActorActivationRegistry("application", 4, 2);
        var first = registry.resolve("ShoppingCart", "caf\u00e9");
        var same = registry.resolve("ShoppingCart", "cafe\u0301");
        assertEquals(first.identity(), same.identity());
        var initializing = registry.activate(first);
        var racing = registry.activate(same);
        assertEquals(initializing.incarnation(), racing.incarnation());
        assertTrue(initializing.initializer());
        assertFalse(racing.initializer());
        assertFalse(initializing.active(), "unpublished activation is not live");
        assertThrows(IllegalStateException.class, () -> registry.tryAdmit(initializing));
        assertThrows(IllegalStateException.class, () -> registry.publish(racing));
        registry.publish(initializing);
        assertTrue(racing.active());
        assertThrows(IllegalStateException.class, () -> registry.publish(initializing));
        var admission1 = registry.tryAdmit(initializing);
        var admission2 = registry.tryAdmit(racing);
        assertNotNull(admission1);
        assertNotNull(admission2);
        assertNull(registry.tryAdmit(initializing), "mailbox credit is bounded");
        assertEquals(2, registry.outstanding(same));
        assertThrows(IllegalStateException.class, () -> registry.deactivate(initializing));
        assertThrows(IllegalStateException.class, registry::close);
        admission1.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
        admission2.settle(VirtualActorActivationRegistry.Disposition.FAILED_CONTROL_NOTIFIED);
        assertThrows(IllegalStateException.class,
                () -> admission1.settle(VirtualActorActivationRegistry.Disposition.CANCELLED_NOTIFIED));
        assertEquals(0, registry.outstanding(first));
        registry.deactivate(initializing);
        assertFalse(racing.active());
        var replacement = registry.activate(same);
        assertTrue(replacement.incarnation() > initializing.incarnation());
        assertThrows(IllegalStateException.class, () -> registry.tryAdmit(initializing));
        registry.publish(replacement);
        assertTrue(replacement.active());
        registry.close();
        assertFalse(replacement.active());
    }

    @Test
    void failedInitializationCanRetryButOldCapabilitiesStayRevoked() {
        var registry = new VirtualActorActivationRegistry("application", 2, 2);
        var logical = registry.resolve("Worker", "one");
        var previous = registry.activate(logical);
        registry.abort(previous);
        assertThrows(IllegalStateException.class, () -> registry.publish(previous));
        var next = registry.activate(logical);
        assertTrue(next.incarnation() > previous.incarnation());
        registry.publish(next);
        assertThrows(IllegalStateException.class, () -> registry.tryAdmit(previous));
        assertNotNull(registry.tryAdmit(next));
    }

    @Test
    void foreignRegistryCannotAuthorizeLogicalOrActivationCapabilities() {
        var first = new VirtualActorActivationRegistry("application", 3, 2);
        var second = new VirtualActorActivationRegistry("application", 3, 2);
        var ref = first.resolve("Worker", "key");
        var activation = first.activate(ref);
        first.publish(activation);
        assertThrows(IllegalArgumentException.class, () -> second.activate(ref));
        assertThrows(IllegalArgumentException.class, () -> second.outstanding(ref));
        assertThrows(IllegalStateException.class, () -> second.tryAdmit(activation));
        assertThrows(IllegalStateException.class, () -> second.deactivate(activation));
        assertThrows(IllegalStateException.class, () -> second.publish(activation));
    }

    @Test
    void identityAndMailboxBudgetsAreBounded() {
        var registry = new VirtualActorActivationRegistry("local", 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("Worker", "k".repeat(257)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("Worker", "x".repeat(1_000_000)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("Worker", "\uD800"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("Worker", "bad\nkey"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve("\uDC00", "key"));
        var first = registry.resolve("Worker", "1");
        assertThrows(IllegalStateException.class, () -> registry.resolve("Worker", "2"));
        var activation = registry.activate(first);
        registry.publish(activation);
        var ticket = registry.tryAdmit(activation);
        assertNotNull(ticket);
        assertNull(registry.tryAdmit(activation));
        ticket.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
        ticket.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
        assertEquals(0, registry.outstanding(first));
        registry.deactivate(activation);
    }
}
