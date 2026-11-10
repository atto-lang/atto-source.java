package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorRuntime.PrivateMemoryBlock;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host runtime integration: real actor-owned heap, real SQLite EVENT journal,
 * verified async commit, new runtime/arena replay and duplicate/stale-epoch denial.
 * It is NOT compiled .ores guest execution and NOT SIGKILL fault injection.
 */
public final class ActorRuntimeEventJournalIntegrationTestMain {
    private ActorRuntimeEventJournalIntegrationTestMain() {}
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static byte[] encode(long cents) {
        return ByteBuffer.allocate(Long.BYTES).putLong(cents).array();
    }
    private static long decode(byte[] bytes) {
        check(bytes.length == Long.BYTES, "unexpected event schema");
        return ByteBuffer.wrap(bytes).getLong();
    }
    private static long replay(SqlitePersistenceStore.Recovery recovered) {
        check(recovered.snapshotVersion() == 0 && recovered.snapshotSchema() == 0,
                "journal-only actor acquired an unexpected snapshot");
        check(recovered.snapshot().length == 0, "event-only actor contains snapshot bytes");
        long sum = 0, nextVersion = 1;
        for (SqlitePersistenceStore.Event event : recovered.events()) {
            check(event.version() == nextVersion++ && event.schemaVersion() == 1,
                    "noncontiguous journal or wrong schema");
            sum = Math.addExact(sum, decode(event.payload()));
        }
        check(recovered.version() == recovered.events().size(), "journal head mismatch");
        return sum;
    }

    private static long deposit(ActorRuntime runtime, BatchPersistenceSidecar sidecar,
            Identity id, long epoch, long currentVersion, long balance,
            String operationId, long amount,
            AtomicReference<PrivateMemoryBlock> memory) throws Exception {
        CompletableFuture<Long> completed = new CompletableFuture<>();
        ActorRuntime.ActorRef<Long> bank = runtime.spawnPrivateTrusted(context -> {
            PrivateMemoryBlock block = context.privateMemory().orElseThrow()
                    .allocatePrivateBytes(Long.BYTES);
            memory.set(block);
            block.writeLong(0, balance);
            ActorPersistenceBridge.Activation binding =
                    ActorPersistenceBridge.bindTrustedCurrentActor(context, id, epoch);
            return (depositCents, turn) -> {
                long before = block.readLong(0);
                check(depositCents > 0, "invalid bank command");
                long proposed = Math.addExact(before, depositCents);
                ActorPersistenceBridge.onEventCompletionInCurrentActor(
                    binding, sidecar, operationId, currentVersion, 1,
                    encode(depositCents), (receipt, failure) -> {
                        try {
                            check(ActorRuntime.currentActorId().orElseThrow().equals(turn.self().id()),
                                    "event completion escaped actor turn lease");
                            if (failure != null) throw new AssertionError(failure);
                            check(receipt.durable() && receipt.status() == Status.COMMITTED
                                    && receipt.version() == currentVersion + 1,
                                    "event receipt not provider-confirmed");
                            check(block.readLong(0) == before,
                                    "actor published uncommitted deposit");
                            block.writeLong(0, proposed);
                            completed.complete(block.readLong(0));
                        } catch (Throwable error) {
                            completed.completeExceptionally(error);
                        } finally {
                            turn.runtime().endCurrentActor();
                        }
                    });
            };
        });
        bank.ready().get(15, TimeUnit.SECONDS);
        bank.send(amount);
        long result = completed.get(15, TimeUnit.SECONDS);
        bank.done().get(15, TimeUnit.SECONDS);
        check(memory.get().closed(), "actor-private memory not revoked after termination");
        return result;
    }

    /**
     * Provider receipts are not commit proof. A malicious/incorrect Store can
     * claim COMMITTED=true while lacking a durable op ledger. The EVENT path
     * must preserve old actor state even under such a fabricated response.
     */
    private static void checkForgedEventReceiptRejected() throws Exception {
        Identity id = new Identity("tenant-bank", "account-forged", "ledger");
        Store forged = new Store() {
            @Override public void grantEpoch(String tenant, String actor, long epoch) {}
            @Override public List<Result> commit(List<Write> writes) {
                Write w = writes.get(0);
                return List.of(new Result(w.operationId(), Status.COMMITTED,
                        w.expectedVersion() + 1, true));
            }
            // verifyDurableCommit() remains the fail-closed default: false.
        };
        try (BatchPersistenceSidecar sidecar = new BatchPersistenceSidecar(
                    id.tenant(), forged, 16, 8192, 1024, 8, 4096, 5);
             ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Long> observed = new CompletableFuture<>();
            ActorRuntime.ActorRef<Long> actor = runtime.spawnPrivateTrusted(context -> {
                PrivateMemoryBlock block = context.privateMemory().orElseThrow()
                        .allocatePrivateBytes(Long.BYTES);
                block.writeLong(0, 7);
                ActorPersistenceBridge.Activation binding =
                        ActorPersistenceBridge.bindTrustedCurrentActor(context, id, 1);
                return (amount, turn) -> {
                    long original = block.readLong(0);
                    ActorPersistenceBridge.onEventCompletionInCurrentActor(
                            binding, sidecar, "forged-event", 0, 1, encode(amount),
                            (receipt, error) -> {
                                try {
                                    check(ActorRuntime.currentActorId().orElseThrow()
                                            .equals(turn.self().id()),
                                            "rejected event callback escaped actor turn");
                                    check(error != null, "forged committed event accepted");
                                    check(block.readLong(0) == original,
                                            "forged event published speculative state");
                                    observed.complete(block.readLong(0));
                                } catch (Throwable failure) {
                                    observed.completeExceptionally(failure);
                                } finally {
                                    turn.runtime().endCurrentActor();
                                }
                            });
                };
            });
            actor.ready().get(15, TimeUnit.SECONDS);
            actor.send(300L);
            check(observed.get(15, TimeUnit.SECONDS) == 7L,
                    "unverified event modified actor state");
            actor.done().get(15, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ores-event-ledger-");
        Path database = directory.resolve("ledger.sqlite");
        Identity id = new Identity("tenant-bank", "account-42", "ledger");
        AtomicReference<PrivateMemoryBlock> firstMemory = new AtomicReference<>();
        AtomicReference<PrivateMemoryBlock> secondMemory = new AtomicReference<>();
        try {
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 BatchPersistenceSidecar sidecar = new BatchPersistenceSidecar(
                         id.tenant(), store, 64, 65536, 1024, 32, 8192, 5);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(id.tenant(), id.actor(), 1);
                check(deposit(runtime, sidecar, id, 1, 0, 0, "deposit-100",
                        10000, firstMemory) == 10000, "first event not published");
            }
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 BatchPersistenceSidecar sidecar = new BatchPersistenceSidecar(
                         id.tenant(), store, 64, 65536, 1024, 32, 8192, 5);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(id.tenant(), id.actor(), 2);
                SqlitePersistenceStore.Recovery old = store.loadFenced(id, 2);
                check(old.version() == 1 && replay(old) == 10000, "first replay mismatch");
                check(deposit(runtime, sidecar, id, 2, 1, replay(old), "deposit-50",
                        5000, secondMemory) == 15000, "second event not published");
                check(secondMemory.get() != firstMemory.get(),
                        "new activation reused old actor-private allocation");
                Result duplicate = store.commit(List.of(new Write(id, "deposit-50", 2, 1,
                        1, Kind.EVENT, encode(5000)))).get(0);
                check(duplicate.status() == Status.DUPLICATE && duplicate.durable()
                        && duplicate.version() == 2,
                        "idempotent retry appended duplicate event");
                check(store.loadFenced(id, 2).events().size() == 2,
                        "duplicate changed event count");
            }
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database)) {
                store.grantEpoch(id.tenant(), id.actor(), 3);
                SqlitePersistenceStore.Recovery latest = store.loadFenced(id, 3);
                check(latest.version() == 2 && replay(latest) == 15000,
                        "committed journal not recovered after restart");
                boolean denied = false;
                try { store.loadFenced(id, 2); }
                catch (SecurityException expected) { denied = true; }
                check(denied, "stale activation restored event state");
                Result stale = store.commit(List.of(new Write(id, "deposit-50", 2,
                        1, 1, Kind.EVENT, encode(5000)))).get(0);
                check(stale.status() == Status.STALE_EPOCH && !stale.durable(),
                        "old writer not fenced at provider");
            }
            checkForgedEventReceiptRejected();
            System.out.println("PASS host ActorRuntime EVENT journal -> SQLite -> new actor arena -> replay");
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try { Files.deleteIfExists(file); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                });
            }
        }
    }
}
