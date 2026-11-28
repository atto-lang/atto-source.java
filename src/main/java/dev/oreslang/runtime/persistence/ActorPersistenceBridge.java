package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresFuture;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;

/**
 * TRUSTED compiler/runtime lowering bridge. Guest Oreslang source never sees
 * a host Store, database path, raw Write, activation epoch or binding token.
 * Source permissions and tenant authentication are enforced by the trusted
 * VM/supervisor before a binding may be minted.
 */
public final class ActorPersistenceBridge {
    private ActorPersistenceBridge() {}

    /** Immutable capability tied to an actual current actor incarnation. */
    public static final class Activation {
        private final ActorRuntime runtime;
        private final ActorRuntime.ActorId owner;
        private final Identity stableIdentity;
        private final long epoch;
        private Activation(ActorRuntime runtime, ActorRuntime.ActorId owner,
                           Identity stableIdentity, long epoch) {
            this.runtime = runtime; this.owner = owner;
            this.stableIdentity = stableIdentity; this.epoch = epoch;
        }
    }

    /**
     * Host-only admission, executed inside initializeActor or an owner-checked
     * context-aware factory. The caller MUST first obtain a trusted tenant/key
     * grant and install a durable provider epoch fence. It must not expose this
     * static host API through guest FFI/reflection.
     */
    public static Activation bindTrustedCurrentActor(
            ActorRuntime.ActorContext<?> context,
            Identity authenticatedIdentity, long trustedEpoch) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(authenticatedIdentity);
        if (trustedEpoch <= 0) throw new IllegalArgumentException("invalid trusted epoch");
        // The host ActorRuntime's UNTRUSTED kind is not a verified process
        // sandbox. An untrusted tenant requires an external, authenticated
        // persistence broker within a physically isolated VM/process.
        if (context.kind() == ActorRuntime.ActorKind.UNTRUSTED)
            throw new SecurityException("untrusted actor persistence requires verified isolation broker");
        ActorRuntime runtime = Objects.requireNonNull(context.runtime());
        requireActorTurn(runtime);
        ActorRuntime.ActorId active = ActorRuntime.currentActorId().orElseThrow();
        if (!active.equals(context.self().id()))
            throw new SecurityException("actor binding must be minted by its own owner");
        return new Activation(runtime, active, authenticatedIdentity, trustedEpoch);
    }

    /**
     * A snapshot payload must already have been copied from sealed actor-owned
     * state on this actor's active turn. The sidecar copies the bytes again
     * before asynchronous admission; no owned arena pointer crosses actors.
     */
    private static Write snapshot(Activation activation, String operationId,
                                  long expectedVersion, int schemaVersion, byte[] ownedBytes) {
        Objects.requireNonNull(activation);
        assertBoundTurn(activation);
        return new Write(activation.stableIdentity, operationId, activation.epoch,
                         expectedVersion, schemaVersion, Kind.SNAPSHOT, ownedBytes);
    }

    public static OresFuture<Result> submitOwnedSnapshot(
            Activation activation, BatchPersistenceSidecar sidecar,
            String operationId, long expectedVersion, int schemaVersion, byte[] ownedBytes) {
        Objects.requireNonNull(sidecar);
        Write write = snapshot(activation, operationId, expectedVersion,
                               schemaVersion, ownedBytes);
        return activation.runtime.ownCurrentActorFuture(OresFuture.from(sidecar.submit(write)));
    }

    /**
     * Registered callback runs on the bounded actor continuation lane after
     * the external storage worker settles a runtime-owned OresFuture.
     */
    public static void onSnapshotCompletionInCurrentActor(
            Activation activation, BatchPersistenceSidecar sidecar,
            String operationId, long expectedVersion, int schemaVersion, byte[] ownedBytes,
            BiConsumer<? super Result, ? super Throwable> continuation) {
        Objects.requireNonNull(sidecar);
        Objects.requireNonNull(continuation);
        Write write = snapshot(activation, operationId, expectedVersion,
                               schemaVersion, ownedBytes);
        ActorRuntime.ContinuationTarget target =
            activation.runtime.captureCurrentContinuationTarget();
        OresFuture<Result> future = OresFuture.from(sidecar.submit(write));
        activation.runtime.enqueueOnCompletion(future, target, (receipt, failure) -> {
            assertBoundTurn(activation);
            continuation.accept(receipt, failure);
        });
    }

    /**
     * Compiler-runtime entry point for a logical event-sourced command.
     * The actor turn owns the immutable candidate; v2 SQLite work runs ONLY
     * on the bounded provider worker. Completion returns through the same
     * incarnation's actor execution lease, not a storage worker callback.
     *
     * A durable command receipt authorizes the caller to publish its candidate
     * state on this turn. A duplicate receipt means the actor must reconcile
     * already recovered state rather than applying the events again.
     */
    public static void onCommandCompletionInCurrentActor(
            Activation activation, SqliteCommandPersistence provider,
            String operationId, long expectedVersion, int schemaVersion,
            byte[] commandBytes, java.util.List<byte[]> events, byte[] resultBytes,
            BiConsumer<? super SqliteCommandPersistence.Receipt, ? super Throwable> continuation) {
        Objects.requireNonNull(activation);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(continuation);
        assertBoundTurn(activation);
        SqliteCommandPersistence.Identity id = commandIdentity(activation);
        SqliteCommandPersistence.Command command = new SqliteCommandPersistence.Command(
            id, operationId, activation.epoch, expectedVersion, schemaVersion,
            commandBytes, events, resultBytes);
        ActorRuntime.ContinuationTarget target =
            activation.runtime.captureCurrentContinuationTarget();
        // An append response is not sufficient evidence for publishing a
        // candidate. Read the authoritative command ledger again under the
        // current epoch fence on the provider worker. This also fails closed
        // if a supervisor advances the epoch after COMMIT but before proof.
        // Both operations are async: no carrier thread calls JDBC.
        OresFuture<SqliteCommandPersistence.Receipt> future =
            OresFuture.from(provider.appendAsync(command).thenCompose(receipt ->
                provider.verifyAsync(id, operationId, activation.epoch, command.commandBytes())
                    .thenApply(proof -> {
                        if (receipt == null || proof == null ||
                            !receipt.operationId().equals(operationId) ||
                            !proof.operationId().equals(operationId) ||
                            receipt.firstVersion() != proof.firstVersion() ||
                            receipt.lastVersion() != proof.lastVersion() ||
                            receipt.eventCount() != proof.eventCount() ||
                            !Arrays.equals(receipt.resultBytes(), proof.resultBytes())) {
                            throw new SqliteCommandPersistence.CommandError("UNVERIFIABLE_COMMIT");
                        }
                        // Preserve append's duplicate flag; the verification
                        // receipt is a historical duplicate even for a new
                        // command that has just committed successfully.
                        return receipt;
                    })
            ));
        activation.runtime.enqueueOnCompletion(future, target, (receipt, failure) -> {
            assertBoundTurn(activation);
            continuation.accept(receipt, failure);
        });
    }

    /** Lost-ACK reconciliation must consult the provider's epoch-fenced ledger. */
    public static void onCommandProofInCurrentActor(
            Activation activation, SqliteCommandPersistence provider,
            String operationId, byte[] commandBytes,
            BiConsumer<? super SqliteCommandPersistence.Receipt, ? super Throwable> continuation) {
        Objects.requireNonNull(activation);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(continuation);
        assertBoundTurn(activation);
        SqliteCommandPersistence.Identity id = commandIdentity(activation);
        byte[] owned = Objects.requireNonNull(commandBytes).clone();
        ActorRuntime.ContinuationTarget target =
            activation.runtime.captureCurrentContinuationTarget();
        OresFuture<SqliteCommandPersistence.Receipt> future =
            OresFuture.from(provider.verifyAsync(id, operationId, activation.epoch, owned));
        activation.runtime.enqueueOnCompletion(future, target, (receipt, failure) -> {
            assertBoundTurn(activation);
            continuation.accept(receipt, failure);
        });
    }

    /** Fenced replay happens on the worker and resumes on the actor's lane. */
    public static void onCommandRecoveryInCurrentActor(
            Activation activation, SqliteCommandPersistence provider,
            BiConsumer<? super SqliteCommandPersistence.Recovery, ? super Throwable> continuation) {
        Objects.requireNonNull(activation);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(continuation);
        assertBoundTurn(activation);
        SqliteCommandPersistence.Identity id = commandIdentity(activation);
        ActorRuntime.ContinuationTarget target =
            activation.runtime.captureCurrentContinuationTarget();
        OresFuture<SqliteCommandPersistence.Recovery> future =
            OresFuture.from(provider.loadFencedAsync(id, activation.epoch));
        activation.runtime.enqueueOnCompletion(future, target, (result, failure) -> {
            assertBoundTurn(activation);
            continuation.accept(result, failure);
        });
    }

    private static SqliteCommandPersistence.Identity commandIdentity(Activation activation) {
        Identity trusted = activation.stableIdentity;
        return new SqliteCommandPersistence.Identity(
            trusted.tenant(), trusted.actor(), trusted.state());
    }

    private static void assertBoundTurn(Activation activation) {
        requireActorTurn(activation.runtime);
        if (!activation.owner.equals(ActorRuntime.currentActorId().orElse(null)))
            throw new SecurityException("persistence binding belongs to another actor incarnation");
    }

    private static void requireActorTurn(ActorRuntime runtime) {
        if (!ActorRuntime.inActorExecution() ||
            ActorRuntime.currentActorRuntime() != runtime ||
            ActorRuntime.currentActorId().isEmpty()) {
            throw new IllegalStateException("actor persistence requires an owned actor turn");
        }
    }
}
