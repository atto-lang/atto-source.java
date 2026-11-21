package dev.oreslang.runtime.accel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts GPU control-channel completions to an actor's reserved, exclusive
 * logical execution lane. A provider callback may run on ANY native carrier
 * thread; it must never invoke the actor's on_panic(), continuations, or
 * application callbacks directly.
 */
public final class GpuActorCompletionBridge {
    private GpuActorCompletionBridge() { }

    @FunctionalInterface
    public interface ReservedControlLane {
        /**
         * Enqueue CONTROL into the actor scheduler's *reserved* control slot.
         * The task must execute on a future admitted single-flight actor turn,
         * never synchronously on the device callback thread.
         */
        void enqueueReservedControl(Runnable task);
    }

    /**
     * Only the actor-lane continuation may complete value().
     * If control admission unexpectedly rejects, the future intentionally
     * remains uncompleted (fail closed rather than running user code on the
     * device carrier). The host can detect the fault via routingFailure().
     */
    public record ActorAwait(
            CompletionStage<GpuDispatchCoordinator.Outcome> value,
            AtomicReference<Throwable> routingFailure) { }

    public static ActorAwait completionOnActorLane(
            GpuDispatchCoordinator.Submission submission,
            ReservedControlLane lane) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(lane, "lane");
        var continuation = new CompletableFuture<GpuDispatchCoordinator.Outcome>();
        var routingFault = new AtomicReference<Throwable>();
        submission.get_done().whenComplete((result, failure) -> {
            try {
                lane.enqueueReservedControl(() -> {
                    if (failure == null) continuation.complete(result);
                    else continuation.completeExceptionally(failure);
                });
            } catch (Throwable enqueueFailure) {
                // Never execute continuation.completeExceptionally() here:
                // CompletableFuture dependents would run on this arbitrary
                // GPU callback carrier, violating actor logical seriality.
                routingFault.compareAndSet(null, enqueueFailure);
            }
        });
        return new ActorAwait(continuation, routingFault);
    }
}
