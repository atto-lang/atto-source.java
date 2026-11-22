package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.compiler.DurableScalarSchemaV1;
import dev.oreslang.compiler.PersistentScalarMirV1;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AOT-safe scalar schema and host async persistence slice. Not full guest parity. */
final class DurableTypedScalarAotTest {
    @TempDir Path temp;

    @Test
    void compilerGeneratedSchemaIdentityIsStableAndCodeGenerationIndependent() {
        var a = DurableScalarSchemaV1.forState("Counter", "count");
        var b = DurableScalarSchemaV1.forState("Counter", "count");
        var renamed = DurableScalarSchemaV1.forState("Counter", "amount");
        assertEquals(a, b);
        assertEquals("atto.actor.Counter.state", a.schemaId());
        assertEquals(1, a.schemaVersion());
        assertEquals(1, a.fieldId());
        assertEquals("i64", a.fieldType());
        assertNotEquals(a.fingerprint(), renamed.fingerprint());
        assertEquals("atto.actor.Counter.increment",
                DurableScalarSchemaV1.forIncrement("Counter").schemaId());
        assertThrows(IllegalArgumentException.class,
                () -> DurableScalarSchemaV1.forState("Counter", "not a field"));
    }

    @Test
    void generatedWireIsVersionedRejectsCorruptionAndOnlyExplicitlyAdmitsLegacy() {
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        assertEquals("atto.actor.Counter.state", plan.state_schema().schemaId());
        byte[] encoded = plan.encode_increment_message(42);
        assertEquals(48, encoded.length);
        assertEquals("4154544f0000000121071b660fb0521c2e822fc966ba000d"
                + "801c601539bfca908891c5c5e5fca0e4000000000000002a",
                java.util.HexFormat.of().formatHex(encoded),
                "shared atto-platform-conformance golden wire");
        assertEquals(42, plan.decode_increment_message(encoded));
        assertEquals(42, plan.decode_increment_message(plan.encode(42)),
                "explicit pre-versioned v0 i64 compatibility");
        encoded[4] = 2; // v2 is unsupported without a migration
        assertThrows(IllegalStateException.class, () -> plan.decode_increment_message(encoded));
        byte[] incompatible = plan.encode_increment_message(10);
        incompatible[10] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> plan.decode_increment_message(incompatible));
        assertThrows(IllegalStateException.class,
                () -> plan.decode_increment_message(new byte[9]));
        assertThrows(IllegalStateException.class,
                () -> DurableScalarSchemaV1.decode(
                        DurableScalarSchemaV1.forIncrement("Another"), plan.encode_increment_message(5)));
    }

    @Test
    void isolatedAsyncProviderPersistsTransactionAndReopens() throws Exception {
        Path db = temp.resolve("async-sqlite.db");
        var address = new DurableActorStore.Address("tenant", "workflow", "run", "node");
        var initial = new DurableActorStore.Snapshot(0, 1, "v1", 0, new byte[] {0});
        var next = new DurableActorStore.Snapshot(0, 1, "v1", 1, new byte[] {1});
        var inbound = new DurableActorStore.Inbound("op1", new byte[] {7});
        var intent = new DurableActorStore.Outbound("intent1", "effect1", new byte[] {9});
        var worker = AsyncSqliteDurableProviderV1.open(db, 8);
        worker.ready().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(worker.bootstrap(address, initial).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(worker.enqueue(address, inbound).toCompletableFuture().get(10, TimeUnit.SECONDS));
        var activation = worker.activate(address).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(1, activation.epoch());
        var turn = new DurableActorStore.Turn(address, activation.epoch(), 0,
                "op1", next, List.of(intent));
        assertEquals(1L, worker.commitTurn(turn).toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(worker.enqueueFenced(address, activation.epoch(), inbound)
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1L, worker.committedDelivery(address, activation.epoch(), inbound)
                .toCompletableFuture().get(10, TimeUnit.SECONDS)
                .orElseThrow().stateVersion());
        assertThrows(ExecutionException.class, () -> worker.enqueueFenced(
                        address, activation.epoch(),
                        new DurableActorStore.Inbound("op1", new byte[] {8}))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        worker.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> worker.activate(address)
                .toCompletableFuture().get(10, TimeUnit.SECONDS));
        try (var reopened = SqliteDurableActorStore.open(db)) {
            var restored = reopened.activate(address);
            assertEquals(1L, restored.snapshot().stateVersion());
            assertEquals(1, restored.pendingOutbox().size());
            assertEquals("effect1", restored.pendingOutbox().getFirst().idempotencyKey());
        }
    }

    @Test
    void providerRejectsUnboundedQueueConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> AsyncSqliteDurableProviderV1.open(temp.resolve("bad.db"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncSqliteDurableProviderV1.open(temp.resolve("bad.db"), 1025));
    }
}
