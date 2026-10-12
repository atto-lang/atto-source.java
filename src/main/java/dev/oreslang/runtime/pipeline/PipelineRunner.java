package dev.oreslang.runtime.pipeline;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Generation-fenced ingress handle, never an ActorRef and never a guest
 * message payload. Old runner instances cannot publish into a replacement.
 */
public final class PipelineRunner {
    private final PipelineController controller;
    private final long generation;
    private final CompletionStage<Void> wholeDone;

    PipelineRunner(PipelineController controller, long generation,
                   CompletionStage<Void> engineDone) {
        this.controller = Objects.requireNonNull(controller);
        this.generation = generation;
        // Keep the physical DONE observer bound to its original generation,
        // even after the controller replaces its active engine session.
        // A minimal stage returns a non-authoritative cancellable copy to
        // callers, not cancellation authority over the real actor group.
        CompletableFuture<Void> observer = new CompletableFuture<>();
        Objects.requireNonNull(engineDone, "physical DONE observer")
                .whenComplete((ignored, failure) -> {
                    if (failure == null) observer.complete(null);
                    else observer.completeExceptionally(failure);
                });
        wholeDone = observer.minimalCompletionStage();
    }

    public long generation() { return generation; }

    /**
     * The returned result is the engine's completed round-trip outcome;
     * submitting concurrently on one runner is intentionally rejected while
     * ActorStream remains a single-flow transport.
     */
    public CompletionStage<Object> send(Object message) {
        return controller.submit(generation, message);
    }

    /** Observe the engine-authenticated all-stage physical-DONE barrier. */
    public CompletionStage<Void> getDone() { return wholeDone; }
}
