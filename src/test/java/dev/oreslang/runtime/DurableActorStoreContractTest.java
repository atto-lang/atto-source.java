package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Immutable port DTO contracts only: no claim that a durable store is installed. */
final class DurableActorStoreContractTest {
    private static final DurableActorStore.Address KEY =
            new DurableActorStore.Address("tenant", "flow", "run", "editor");

    @Test
    void addressesRejectMissingTenantAndNodeIdentities() {
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Address(" ", "flow", "run", "editor"));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Address("tenant", "flow", "run", ""));
    }

    @Test
    void snapshotAndMessagesDoNotLeakMutablePayloadArrays() {
        byte[] input = {1, 2, 3};
        var snap = new DurableActorStore.Snapshot(0, 1, "code-v1", 0, input);
        var inbox = new DurableActorStore.Inbound("delivery-1", input);
        var outbox = new DurableActorStore.Outbound("op-1", "run:op-1", input);
        input[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, snap.encodedState());
        assertArrayEquals(new byte[]{1, 2, 3}, inbox.payload());
        assertArrayEquals(new byte[]{1, 2, 3}, outbox.payload());
        snap.encodedState()[1] = 8;
        inbox.payload()[1] = 8;
        outbox.payload()[1] = 8;
        assertArrayEquals(new byte[]{1, 2, 3}, snap.encodedState());
        assertArrayEquals(new byte[]{1, 2, 3}, inbox.payload());
        assertArrayEquals(new byte[]{1, 2, 3}, outbox.payload());
    }

    @Test
    void activationAndTurnDefensivelyCopyListsAndEnforceVersion() {
        var old = new DurableActorStore.Snapshot(0, 1, "code-v1", 0, new byte[]{1});
        var next = new DurableActorStore.Snapshot(0, 1, "code-v1", 1, new byte[]{2});
        var entries = new ArrayList<DurableActorStore.Inbound>();
        entries.add(new DurableActorStore.Inbound("event", new byte[]{1}));
        var activation = new DurableActorStore.Activation(1, old, entries, List.of());
        entries.clear();
        assertEquals(1, activation.inbox().size());
        assertThrows(UnsupportedOperationException.class, () -> activation.inbox().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Turn(KEY, 1, 0, "event", old, List.of()));
        var turn = new DurableActorStore.Turn(KEY, 1, 0, "event", next, List.of());
        assertEquals(1, turn.nextSnapshot().stateVersion());
        var intentA = new DurableActorStore.Outbound("a", "same-key", new byte[]{1});
        var intentB = new DurableActorStore.Outbound("b", "same-key", new byte[]{2});
        var intentC = new DurableActorStore.Outbound("a", "other-key", new byte[]{3});
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Turn(KEY, 1, 0, "event", next, List.of(intentA, intentB)));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Turn(KEY, 1, 0, "event", next, List.of(intentA, intentC)));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Turn(KEY, 1, Long.MAX_VALUE, "event", next, List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Activation(0, old, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DurableActorStore.Snapshot(5, 1, "code-v1", 0, new byte[0]));
    }
}
