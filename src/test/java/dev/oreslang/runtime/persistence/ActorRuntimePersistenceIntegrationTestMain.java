package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorRuntime.PrivateMemoryBlock;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java ActorRuntime + real SQLite host integration. This exercises authentic
 * actor scheduling and actor-owned private-memory allocation, but NOT guest
 * .ores compiler lowering. Requires the opt-in SQLite JDBC runtime dependency.
 */
public final class ActorRuntimePersistenceIntegrationTestMain {
    private ActorRuntimePersistenceIntegrationTestMain() {}
    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
    private static byte[] encode(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
    private static long decode(byte[] value) {
        if (value.length != Long.BYTES) throw new AssertionError("invalid state schema");
        return ByteBuffer.wrap(value).getLong();
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ores-actor-sqlite-");
        Path database = directory.resolve("persisted.sqlite");
        Identity key = new Identity("tenant-a", "ledger-42", "balance");
        AtomicReference<PrivateMemoryBlock> firstMemory = new AtomicReference<>();
        AtomicReference<ActorPersistenceBridge.Activation> oldGrant = new AtomicReference<>();
        try {
            // Activation 1: persistent bytes originate ONLY from an actor-owned
            // direct-memory block, read inside its serialized actor execution.
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 BatchPersistenceSidecar sidecar = new BatchPersistenceSidecar(
                     key.tenant(), store, 64, 1 << 16, 1024, 32, 8192, 5);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(key.tenant(), key.actor(), 1);
                CompletableFuture<Result> persisted = new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> actor = runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock block = context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                    firstMemory.set(block);
                    block.writeLong(0, 1);
                    ActorPersistenceBridge.Activation binding =
                        ActorPersistenceBridge.bindTrustedCurrentActor(context, key, 1);
                    oldGrant.set(binding);
                    return (delta, turn) -> {
                        // Compute an owned candidate without publishing it to the
                        // actor's mutable state before provider durability.
                        long next = Math.addExact(block.readLong(0), delta);
                        check(block.readLong(0) == 1L,
                            "uncommitted mutation became visible before submission");
                        ActorPersistenceBridge.onSnapshotCompletionInCurrentActor(
                            binding, sidecar, "first-deposit", 0, 1, encode(next),
                            (receipt, failure) -> {
                                // This callback runs under the actor-wide execution
                                // lease, never on the blocking SQLite worker.
                                try {
                                    check(ActorRuntime.currentActorId()
                                            .orElseThrow().equals(turn.self().id()),
                                        "storage callback escaped actor's execution lease");
                                    if (failure != null) throw new AssertionError(failure);
                                    check(receipt.durable() &&
                                        receipt.status() == Status.COMMITTED &&
                                        receipt.version() == 1, "commit receipt not durable");
                                    check(block.readLong(0) == 1L,
                                        "durable state was published before commit callback");
                                    // Only the current actor turn may now make the
                                    // provider-confirmed candidate visible.
                                    block.writeLong(0, next);
                                    check(block.readLong(0) == next,
                                        "committed mutation not published on actor carrier");
                                    persisted.complete(receipt);
                                } catch (Throwable problem) {
                                    persisted.completeExceptionally(problem);
                                } finally {
                                    turn.runtime().endCurrentActor();
                                }
                            });
                    };
                });
                actor.ready().get(15, TimeUnit.SECONDS);
                actor.send(41L);
                // Host waits here; the actor dispatcher never blocks on SQLite.
                persisted.get(15, TimeUnit.SECONDS);
                actor.done().get(15, TimeUnit.SECONDS);
                check(firstMemory.get().closed(), "first activation arena not revoked");
                // Host and sibling actors cannot reuse an actor-incarnation token.
                boolean denied = false;
                try { ActorPersistenceBridge.submitOwnedSnapshot(
                    oldGrant.get(), sidecar, "forged", 1, 1, encode(999)); }
                catch (IllegalStateException | SecurityException expected) { denied = true; }
                check(denied, "persistence token usable outside its actor execution");
            }
            // A genuinely new ActorRuntime and new actor-local heap; all state
            // is reloaded through the authoritative persistent provider.
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(key.tenant(), key.actor(), 2);
                boolean staleRestoreDenied = false;
                try { store.loadFenced(key, 1); }
                catch (SecurityException expected) { staleRestoreDenied = true; }
                check(staleRestoreDenied, "stale actor incarnation restored persistent state");
                var recovered = store.loadFenced(key, 2);
                check(recovered.version() == 1 &&
                    recovered.snapshotVersion() == 1 &&
                    recovered.snapshotSchema() == 1 &&
                    recovered.events().isEmpty(), "durable recovery mismatch");
                long restoredBalance = decode(recovered.snapshot());
                CompletableFuture<Long> activated = new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> second = runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock block = context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                    block.writeLong(0, restoredBalance);
                    return (request, turn) -> {
                        check(block != firstMemory.get(),
                            "new activation reused previous actor memory allocation");
                        check(block.readLong(0) == 42L,
                            "persisted balance not restored into fresh actor arena");
                        activated.complete(block.readLong(0));
                        turn.runtime().endCurrentActor();
                    };
                });
                second.ready().get(15, TimeUnit.SECONDS);
                second.send(0L);
                check(activated.get(15, TimeUnit.SECONDS) == 42L,
                    "activation result differs from persisted state");
                second.done().get(15, TimeUnit.SECONDS);
                check(firstMemory.get().closed(),
                    "old actor memory resurrected after new activation");
                // A previous incarnation cannot replay a success receipt.
                var old = store.commit(java.util.List.of(
                    new Write(key, "first-deposit", 1, 0, 1,
                        Kind.SNAPSHOT, encode(42))));
                check(old.get(0).status() == Status.STALE_EPOCH,
                    "stale first activation still has write authority");
            }
            System.out.println("PASS host ActorRuntime actor-owned heap -> SQLite -> stop -> fresh activation restore");
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                });
            }
        }
    }
}
