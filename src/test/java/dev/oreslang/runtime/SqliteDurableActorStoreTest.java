package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteDurableActorStoreTest {
    @TempDir Path tmp;

    private static final DurableActorStore.Address A =
            new DurableActorStore.Address("tenant-a", "workflow", "run", "counter");
    private static final DurableActorStore.Address B =
            new DurableActorStore.Address("tenant-b", "workflow", "run", "counter");

    private Path database() { return tmp.resolve("persistent-actors.db"); }

    private static DurableActorStore.Snapshot snapshot(long version, int value) {
        return new DurableActorStore.Snapshot(0, 1, "code-v1", version, new byte[] {(byte) value});
    }

    private static DurableActorStore.Inbound mail(String id) {
        return new DurableActorStore.Inbound(id, id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static DurableActorStore.Outbound intent(String id, String key) {
        return new DurableActorStore.Outbound(id, key, new byte[] {42});
    }

    private static DurableActorStore.Turn turn(long epoch, long version,
            String consumed, List<DurableActorStore.Outbound> outgoing) {
        return new DurableActorStore.Turn(A, epoch, version, consumed,
                snapshot(version + 1, (int) version + 1), outgoing);
    }

    @Test
    void persistsAcrossReopenAndReplaysUnacknowledgedOutbox() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            assertTrue(store.bootstrap(A, snapshot(0, 0)));
            assertFalse(store.bootstrap(A, snapshot(0, 99)), "bootstrap cannot reset state");
            assertTrue(store.enqueue(A, mail("one")));
            assertFalse(store.enqueue(A, mail("one")));
            var activation = store.activate(A);
            assertEquals(1, activation.epoch());
            assertEquals(1, activation.inbox().size());
            assertEquals(1, store.commitTurn(turn(activation.epoch(), 0, "one",
                    List.of(intent("out-1", "key-1")))));
            var original = store.committedDelivery(A, activation.epoch(), mail("one")).orElseThrow();
            assertEquals(1L, original.stateVersion());
            assertArrayEquals(new byte[] {1}, original.encodedState());
            store.hibernate(A, activation.epoch());
            assertFalse(store.enqueue(A, mail("one")), "consumed deliveries remain deduplicated");
        }
        try (var reopened = SqliteDurableActorStore.open(database())) {
            var restored = reopened.activate(A);
            assertEquals(2, restored.epoch());
            assertEquals(1, restored.snapshot().stateVersion());
            assertArrayEquals(new byte[] {1}, restored.snapshot().encodedState());
            assertTrue(restored.inbox().isEmpty());
            assertEquals(1, restored.pendingOutbox().size());
            assertEquals("key-1", restored.pendingOutbox().getFirst().idempotencyKey());
            assertEquals(1L, reopened.committedDelivery(A, restored.epoch(), mail("one"))
                    .orElseThrow().stateVersion());
            assertThrows(IllegalStateException.class, () -> reopened.committedDelivery(
                    A, restored.epoch(), new DurableActorStore.Inbound("one", new byte[] {99})));
            assertTrue(reopened.acknowledge(A, restored.epoch(), "out-1", "receipt"));
            assertTrue(reopened.acknowledge(A, restored.epoch(), "out-1", "receipt"));
            assertFalse(reopened.acknowledge(A, restored.epoch(), "out-1", "other"));
            assertFalse(reopened.acknowledge(A, restored.epoch() - 1, "out-1", "receipt"));
            assertTrue(reopened.activate(A).pendingOutbox().isEmpty());
        }
    }

    @Test
    void duplicateDeliveryIdMustBindOriginalBytesBeforeAndAfterConsumption() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            DurableActorStore.Inbound original = new DurableActorStore.Inbound(
                    "command-1", new byte[] {1, 2, 3});
            DurableActorStore.Inbound conflict = new DurableActorStore.Inbound(
                    "command-1", new byte[] {1, 2, 4});
            assertTrue(store.enqueue(A, original));
            assertFalse(store.enqueue(A, original));
            assertThrows(IllegalStateException.class, () -> store.enqueue(A, conflict));
            long epoch = store.activate(A).epoch();
            assertFalse(store.enqueueFenced(A, epoch, original));
            assertThrows(IllegalStateException.class,
                    () -> store.enqueueFenced(A, epoch, conflict));
            assertEquals(1L, store.commitTurn(turn(epoch, 0, "command-1", List.of())));
            assertFalse(store.enqueue(A, original));
            assertThrows(IllegalStateException.class, () -> store.enqueue(A, conflict));
            assertFalse(store.enqueueFenced(A, epoch, original));
            assertThrows(IllegalStateException.class,
                    () -> store.enqueueFenced(A, epoch, conflict));
            assertEquals(1, store.activate(A).snapshot().stateVersion());
            assertThrows(IllegalStateException.class,
                    () -> store.enqueueFenced(A, epoch, original),
                    "stale epoch must be checked before matching duplicate ID");
        }
    }

    @Test
    void staleActivationCannotCommitAcknowledgeOrHibernate() throws Exception {
        try (var oldStore = SqliteDurableActorStore.open(database());
             var newStore = SqliteDurableActorStore.open(database())) {
            assertTrue(oldStore.bootstrap(A, snapshot(0, 0)));
            assertTrue(oldStore.enqueue(A, mail("one")));
            long oldEpoch = oldStore.activate(A).epoch();
            long newEpoch = newStore.activate(A).epoch();
            assertEquals(oldEpoch + 1, newEpoch);
            assertThrows(IllegalStateException.class, () ->
                    oldStore.commitTurn(turn(oldEpoch, 0, "one", List.of())));
            assertThrows(IllegalStateException.class, () -> oldStore.hibernate(A, oldEpoch));
            assertFalse(oldStore.acknowledge(A, oldEpoch, "unknown", "receipt"));
            assertEquals(1, newStore.commitTurn(turn(newEpoch, 0, "one", List.of())));
            assertEquals(1, newStore.activate(A).snapshot().stateVersion());
        }
    }

    @Test
    void uniqueOutboxIdentityConflictRollsBackEntireTurn() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            store.enqueue(A, mail("one"));
            long epoch = store.activate(A).epoch();
            store.commitTurn(turn(epoch, 0, "one", List.of(intent("id-1", "idem-1"))));
            store.enqueue(A, mail("two"));
            assertThrows(IllegalStateException.class, () ->
                    store.commitTurn(turn(epoch, 1, "two",
                            List.of(intent("id-2", "idem-1")))));
            var restored = store.activate(A);
            assertEquals(1, restored.snapshot().stateVersion());
            assertEquals("two", restored.inbox().getFirst().deliveryId());
            assertTrue(store.committedDelivery(A, restored.epoch(), mail("two")).isEmpty(),
                    "rolled-back outbox conflict must not leave a committed receipt");
            assertEquals(1, restored.pendingOutbox().size());
            assertEquals(2, store.commitTurn(turn(restored.epoch(), 1, "two",
                    List.of(intent("id-2", "idem-2")))));
        }
    }

    @Test
    void incompatibleSnapshotAndMissingDeliveryNeverCommit() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            store.enqueue(A, mail("one"));
            long epoch = store.activate(A).epoch();
            var changedCode = new DurableActorStore.Snapshot(
                    0, 1, "code-v2", 1, new byte[] {1});
            assertThrows(IllegalStateException.class, () -> store.commitTurn(
                    new DurableActorStore.Turn(A, epoch, 0, "one", changedCode, List.of())));
            assertThrows(IllegalStateException.class, () -> store.commitTurn(
                    turn(epoch, 0, "unadmitted", List.of())));
            assertEquals(0, store.activate(A).snapshot().stateVersion());
        }
    }

    @Test
    void rejectsChecksumMismatchBeforeReturningCorruptState() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database());
                 PreparedStatement q = conn.prepareStatement(
                         "UPDATE durable_actors SET state=? WHERE tenant=?")) {
                q.setBytes(1, new byte[] {99});
                q.setString(2, "tenant-a");
                assertEquals(1, q.executeUpdate());
            }
            assertThrows(IllegalStateException.class, () -> store.activate(A));
        }
    }

    @Test
    void tenantsAndDormantActorsHaveSeparateInboxes() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            store.bootstrap(B, snapshot(0, 7));
            store.enqueue(A, mail("same-id"));
            store.enqueue(B, mail("same-id"));
            assertEquals(1, store.activate(A).inbox().size());
            var other = store.activate(B);
            assertEquals(1, other.inbox().size());
            assertArrayEquals(new byte[] {7}, other.snapshot().encodedState());
            assertFalse(store.enqueue(A, mail("same-id")));
            assertThrows(IllegalStateException.class,
                    () -> store.activate(new DurableActorStore.Address("absent", "flow", "run", "node")));
        }
    }

    @Test
    void enforcesPayloadBudgetBeforeDatabaseWrite() throws Exception {
        try (var store = SqliteDurableActorStore.open(database())) {
            store.bootstrap(A, snapshot(0, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    store.enqueue(A, new DurableActorStore.Inbound(
                            "large", new byte[16 * 1024 * 1024 + 1])));
            assertTrue(store.activate(A).inbox().isEmpty());
        }
    }
}
