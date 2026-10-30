package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresFuture;
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
