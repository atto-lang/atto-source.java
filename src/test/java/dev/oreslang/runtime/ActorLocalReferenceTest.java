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
    void onlyDeeplyImmutableScalarValuesMayBeReadByCopy() {
        Object actor = new Object();
        var scalar = ActorLocalReference.createForDomain(actor, 11L);
        assertEquals(11L, ActorLocalReference.getCopyForDomain(scalar, actor));

        var mutableNumber = ActorLocalReference.createForDomain(
                actor, new java.util.concurrent.atomic.AtomicInteger(5));
        assertThrows(IllegalArgumentException.class, () ->
                ActorLocalReference.getCopyForDomain(mutableNumber, actor));

        var mutableCollection = ActorLocalReference.createForDomain(
                actor, new ArrayList<>(List.of(1)));
        assertThrows(IllegalArgumentException.class, () ->
                ActorLocalReference.getCopyForDomain(mutableCollection, actor));
        assertThrows(SecurityException.class, () ->
                ActorLocalReference.getCopyForDomain(scalar, new Object()));
    }

    @Test
    void nestedActorLocalHandlesCannotBeFrozenIntoMessages() {
        Object actor = new Object();
        var ref = ActorLocalReference.createForDomain(actor, 10L);
        var weak = ActorLocalReference.downgradeForDomain(ref, actor);
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(ref));
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(List.of(ref)));
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(java.util.Map.of("local", weak)));
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(List.of(java.util.Map.of("local", ref))));
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
