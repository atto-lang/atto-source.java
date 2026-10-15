package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request-correlated one-shot reply slot for the future compiled unary
 * actor adapter. This is a host substrate, NOT guest request()/run() lowering
 * and not an HTTP response writer.
 *
 * Minting and settlement are package-private, so source programs cannot
 * create or complete slots directly. The authoritative runtime must bind the
 * slot to a code image, actor generation and message admission ticket.
 */
public final class ActorUnaryReplySlot<R> {
    public enum State { PENDING, REPLIED, REJECTED, CANCELLED }

    private final ActorRuntime.ActorId owner;
    private final long generation;
    private final UUID requestId;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final AtomicBoolean dispatchClaimed = new AtomicBoolean();
    private final CompletableFuture<R> result = new CompletableFuture<>();

    ActorUnaryReplySlot(ActorRuntime.ActorId owner, long generation, UUID requestId) {
        this.owner = Objects.requireNonNull(owner, "reply actor");
        if (generation < 0) throw new IllegalArgumentException("negative actor incarnation");
        this.generation = generation;
        this.requestId = Objects.requireNonNull(requestId, "reply request ID");
    }

    public UUID requestId() { return requestId; }
    public State state() { return state.get(); }
    public CompletionStage<R> result() { return result.minimalCompletionStage(); }

    /**
     * Only the trusted actor adapter can settle a reply. The actor incarnation
     * and originating request must match; a late result from a prior restart
     * or a different request cannot settle this slot.
     */
    boolean reply(ActorRuntime.ActorId actor, long actorGeneration,
                  UUID responseTo, R value) {
        checkIdentity(actor, actorGeneration, responseTo);
        Objects.requireNonNull(value, "unary reply must have a value");
        if (!state.compareAndSet(State.PENDING, State.REPLIED)) return false;
        result.complete(value);
        return true;
    }

    boolean reject(ActorRuntime.ActorId actor, long actorGeneration,
                   UUID responseTo, Throwable cause) {
        checkIdentity(actor, actorGeneration, responseTo);
        Objects.requireNonNull(cause, "reply rejection cause");
        if (!state.compareAndSet(State.PENDING, State.REJECTED)) return false;
        result.completeExceptionally(cause);
        return true;
    }

    /** Cancels the logical request; no later reply can be published. */
    boolean cancel() {
        if (!state.compareAndSet(State.PENDING, State.CANCELLED)) return false;
        result.completeExceptionally(new CancellationException("actor unary request cancelled"));
        return true;
    }

    /**
     * Claim dispatch exactly once, BEFORE any middleware/actor work. A check
     * of PENDING without a claim allows two concurrent callers to run the
     * whole middleware chain and race only at terminal reply publication.
     */
    void claimDispatch(ActorRuntime.ActorId actor, long actorGeneration) {
        checkIdentity(actor, actorGeneration, requestId);
        if (state.get() != State.PENDING) {
            throw new IllegalStateException("actor unary reply ticket already settled");
        }
        if (!dispatchClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("actor unary request has already been dispatched");
        }
        if (state.get() != State.PENDING) {
            throw new IllegalStateException("actor unary reply ticket was cancelled before dispatch");
        }
    }

    private void checkIdentity(ActorRuntime.ActorId actor, long actorGeneration,
                               UUID responseTo) {
        if (!owner.equals(actor) || generation != actorGeneration
                || !requestId.equals(responseTo)) {
            throw new SecurityException("actor unary reply identity or generation mismatch");
        }
    }
}
