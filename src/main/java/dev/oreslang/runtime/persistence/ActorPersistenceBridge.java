package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresFuture;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Trusted compiler/runtime lowering bridge for actor persistence requests.
 * Not a guest API: the host must authenticate tenant ownership, derive epoch
 * from the activation directory and serialize sealed actor state before calling.
 */
public final class ActorPersistenceBridge {
    private ActorPersistenceBridge() {}

    /** May be awaited by a source actor without parking the actor's carrier. */
    public static OresFuture<BatchPersistenceSidecar.Result> submitCurrentActor(
            ActorRuntime runtime,
            BatchPersistenceSidecar sidecar,
            BatchPersistenceSidecar.Write write) {
        requireActorTurn(runtime);
        Objects.requireNonNull(sidecar, "sidecar");
        Objects.requireNonNull(write, "write");
        return runtime.ownCurrentActorFuture(OresFuture.from(sidecar.submit(write)));
    }

    /**
     * Guest completion callback runs through ActorRuntime's bounded continuation
     * mailbox/actor-wide lease, never on the sidecar storage worker.
     */
    public static void onCompletionInCurrentActor(
            ActorRuntime runtime,
            BatchPersistenceSidecar sidecar,
            BatchPersistenceSidecar.Write write,
            BiConsumer<? super BatchPersistenceSidecar.Result, ? super Throwable> continuation) {
        requireActorTurn(runtime);
        Objects.requireNonNull(sidecar, "sidecar");
        Objects.requireNonNull(write, "write");
        Objects.requireNonNull(continuation, "continuation");
        ActorRuntime.ContinuationTarget target = runtime.captureCurrentContinuationTarget();
        OresFuture<BatchPersistenceSidecar.Result> future = OresFuture.from(sidecar.submit(write));
        runtime.enqueueOnCompletion(future, target, continuation);
    }

    private static void requireActorTurn(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (!ActorRuntime.inActorExecution() || ActorRuntime.currentActorRuntime() != runtime) {
            throw new IllegalStateException("actor persistence requires an owned actor turn");
        }
    }
}
