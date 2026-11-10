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
 * Real host ActorRuntime + SQLite event journal test. Never promote this to
 * source-compiled .atto guest parity or complete lost-ACK deduplication.
 */
public final class EventSourcedActorRuntimeIntegrationTestMain {
    private EventSourcedActorRuntimeIntegrationTestMain() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] encode(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
    private static long decode(byte[] encoded) {
        if (encoded.length != Long.BYTES) throw new IllegalStateException("CORRUPT_EVENT_PAYLOAD");
        return ByteBuffer.wrap(encoded).getLong();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("atto-event-journal-");
        Path database = dir.resolve("tenant.sqlite");
        Identity id = new Identity("tenant-a", "bank-account-42", "main");
        AtomicReference<PrivateMemoryBlock> oldMemory = new AtomicReference<>();
        try {
            // First actor commits a domain event, then loses its success reply
            // and dies without publishing the new in-memory balance.
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 BatchPersistenceSidecar sidecar = new BatchPersistenceSidecar(
                         id.tenant(), store, 32, 8192, 1024, 16, 4096, 5);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(id.tenant(), id.actor(), 1);
                CompletableFuture<Long> committed = new CompletableFuture<>();
                ActorRuntime.ActorRef<Long> account = runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock memory = context.privateMemory().orElseThrow()
                            .allocatePrivateBytes(Long.BYTES);
                    oldMemory.set(memory);
                    memory.writeLong(0, 0);
                    ActorPersistenceBridge.Activation binding =
                            ActorPersistenceBridge.bindTrustedCurrentActor(context, id, 1);
                    return (deposit, turn) -> {
                        long previouslyPublished = memory.readLong(0);
                        ActorPersistenceBridge.onEventCompletionInCurrentActor(
                                binding, sidecar, "deposit-op-42", 0, 1,
                                encode(deposit), (receipt, failure) -> {
                                    try {
                                        if (failure != null) throw new AssertionError(failure);
                                        check(ActorRuntime.currentActorId().orElseThrow()
                                                .equals(turn.self().id()),
                                                "storage continuation escaped owning actor");
                                        check(receipt.durable() && receipt.version() == 1
                                                && receipt.status() == Status.COMMITTED,
                                                "event was not durably committed");
                                        check(memory.readLong(0) == previouslyPublished,
                                                "tentative balance became visible before commit");
                                        // Explicit failpoint: lost reply / crash after durable commit.
                                        // Do not publish new state or reply to the caller.
                                        committed.complete(receipt.version());
                                    } catch (Throwable failureInCallback) {
                                        committed.completeExceptionally(failureInCallback);
                                    } finally {
                                        turn.runtime().endCurrentActor();
                                    }
                                });
                    };
                });
                account.ready().get(15, TimeUnit.SECONDS);
                account.send(42L);
                check(committed.get(15, TimeUnit.SECONDS) == 1,
                        "expected exactly one durable deposit event");
                account.done().get(15, TimeUnit.SECONDS);
                check(oldMemory.get().closed(), "retired private actor arena remained live");
            }

            // Fresh runtime and a new private actor memory generation.
            // Recovery must obtain a provider-fenced consistent journal read.
            try (SqlitePersistenceStore store = new SqlitePersistenceStore(database);
                 ActorRuntime runtime = new ActorRuntime()) {
                store.grantEpoch(id.tenant(), id.actor(), 2);
                boolean staleDenied = false;
                try { store.loadFenced(id, 1); }
                catch (SecurityException expected) { staleDenied = true; }
                check(staleDenied, "old actor epoch passed the journal recovery fence");

                var restored = store.loadFenced(id, 2);
                check(restored.version() == 1 && restored.snapshotVersion() == 0
                        && restored.events().size() == 1,
                        "recovery did not load one committed event");
                long balance = 0;
                long eventVersion = 0;
                for (SqlitePersistenceStore.Event event : restored.events()) {
                    check(event.version() == eventVersion + 1 && event.schemaVersion() == 1,
                            "journal replay was not contiguous/schema-correct");
                    balance = Math.addExact(balance, decode(event.payload()));
                    eventVersion = event.version();
                }
                check(balance == 42 && eventVersion == restored.version(),
                        "replayed balance is wrong");

                CompletableFuture<Long> published = new CompletableFuture<>();
                final long recoveredBalance = balance;
                ActorRuntime.ActorRef<Long> restarted = runtime.spawnPrivateTrusted(context -> {
                    PrivateMemoryBlock memory = context.privateMemory().orElseThrow()
                            .allocatePrivateBytes(Long.BYTES);
                    check(memory != oldMemory.get(), "retired arena was reused");
                    memory.writeLong(0, recoveredBalance);
                    return (ignored, turn) -> {
                        published.complete(memory.readLong(0));
                        turn.runtime().endCurrentActor();
                    };
                });
                restarted.ready().get(15, TimeUnit.SECONDS);
                restarted.send(0L);
                check(published.get(15, TimeUnit.SECONDS) == 42,
                        "new incarnation did not publish replayed event state");
                restarted.done().get(15, TimeUnit.SECONDS);

                // Current provider cannot reconcile an operation written by an
                // older epoch into a new success reply. It must NOT append again.
                // Durable inbox/operation resolution remains an explicit P0 gap.
                Result retry = store.commit(List.of(new Write(id, "deposit-op-42",
                        2, 0, 1, Kind.EVENT, encode(42)))).get(0);
                check(retry.status() == Status.OP_ID_CONFLICT && !retry.durable(),
                        "cross-epoch retry silently reissued an already committed event");
                check(store.loadFenced(id, 2).events().size() == 1,
                        "duplicate deposit changed journal size");
            }
            System.out.println("PASS host event commit -> lost ACK -> private arena retirement -> fenced replay");
        } finally {
            try (var entries = Files.walk(dir)) {
                entries.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try { Files.deleteIfExists(file); }
                    catch (Exception issue) { throw new IllegalStateException(issue); }
                });
            }
        }
    }
}
