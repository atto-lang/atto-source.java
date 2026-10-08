package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalReferenceTest {
    @Test
    void publicFactoryRequiresActiveActorTurn() {
        assertThrows(IllegalStateException.class, () -> ActorLocalReference.create(1L));
    }

    @Test
    void strongHandlesShareOneCellButRequireExplicitCloning() {
        Object actor = new Object();
        ActorLocalReference.Strong<Long> original =
                ActorLocalReference.createForDomain(actor, 1L);
        ActorLocalReference.Strong<Long> alias =
                ActorLocalReference.cloneForDomain(original, actor);

        assertEquals(1L, ActorLocalReference.swapForDomain(alias, actor, 9L));
        assertEquals(9L, ActorLocalReference.swapForDomain(original, actor, 3L));
        assertThrows(SecurityException.class, () ->
                ActorLocalReference.cloneForDomain(original, new Object()));
        assertThrows(SecurityException.class, () ->
                ActorLocalReference.swapForDomain(alias, new Object(), 8L));
    }

    @Test
    void weakUpgradeDoesNotGiveCrossActorAccess() {
        Object actor = new Object();
        ActorLocalReference.Strong<Long> strong =
                ActorLocalReference.createForDomain(actor, 2L);
        ActorLocalReference.Weak<Long> weak =
                ActorLocalReference.downgradeForDomain(strong, actor);

        ActorLocalReference.Strong<Long> upgraded =
                ActorLocalReference.upgradeForDomain(weak, actor).orElseThrow();
        assertEquals(2L, ActorLocalReference.swapForDomain(upgraded, actor, 7L));
        assertThrows(SecurityException.class, () ->
                ActorLocalReference.upgradeForDomain(weak, new Object()));
    }

    @Test
    void managedReferenceGraphMayContainIdentityCyclesWithoutCopyingOwnedValues() {
        Object actor = new Object();
        List<Object> node = new ArrayList<>();
        ActorLocalReference.Strong<List<Object>> owner =
                ActorLocalReference.createForDomain(actor, node);
        ActorLocalReference.Strong<List<Object>> reference =
                ActorLocalReference.cloneForDomain(owner, actor);
        node.add(reference); // one explicit handle edge forms the cycle

        assertSame(node, ActorLocalReference.swapForDomain(owner, actor, new ArrayList<>()));
        assertEquals(1, node.size());
        assertSame(reference, node.getFirst());

        // Such cycles are local; never deep-freeze them for mailbox transport.
        assertThrows(RuntimeException.class, () -> ActorRuntime.freeze(owner));
    }
}
