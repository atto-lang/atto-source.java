package dev.oreslang.runtime;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Trusted host-only composition of typed middleware decisions with an
 * incarnation/request-fenced reply slot. No direct guest actor run() or
 * transport response writer is exposed here.
 *
 * Preserve Reply<R>'s immutable response draft for the eventual HTTP adapter.
 * A middleware rejection settles the slot exceptionally, never as a forged
 * application R. The authoritative actor scheduler must mint the ticket only
 * after mailbox admission and revoke it on stop/cancellation.
 */
public final class ActorUnaryReplyBridge<R> {
    public static final class RejectedException extends IllegalStateException {
        private final int status;
        public RejectedException(int status, String reason) {
            super(Objects.requireNonNull(reason));
            if (status < 400 || status > 599) {
                throw new IllegalArgumentException("invalid rejected response status");
            }
            this.status = status;
        }
        public int status() { return status; }
    }

    private final ActorMiddlewareValuePipeline<R> middleware;

    public ActorUnaryReplyBridge(ActorMiddlewareValuePipeline<R> middleware) {
        this.middleware = Objects.requireNonNull(middleware, "unary middleware");
    }

    /**
     * This bridge performs no request admission itself. The runtime-only slot
     * binds actor incarnation and request ID. A wrong owner is rejected before
     * middleware or actor terminal work begins.
     */
    CompletionStage<ActorMiddlewareValuePipeline.Reply<R>> dispatch(
            ActorRuntime.ActorId actor, long generation,
            ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<R>> ticket,
            ActorMiddlewarePipeline.Envelope request,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(ticket, "reply ticket");
        Objects.requireNonNull(request, "request envelope");
        Objects.requireNonNull(cancelled, "cancellation observer");
        // The claim must precede middleware entry: pending-state checks
        // alone do not protect against two racing dispatch callers.
        ticket.claimDispatch(actor, generation);
        UUID responseTo = ticket.requestId();
        try {
            BooleanSupplier cancelledOrRevoked = () -> {
                if (ticket.state() == ActorUnaryReplySlot.State.CANCELLED) return true;
                if (cancelled.getAsBoolean()) {
                    ticket.cancel();
                    return true;
                }
                return false;
            };
            if (cancelledOrRevoked.getAsBoolean()) return ticket.result();
            middleware.invoke(request, cancelledOrRevoked).whenComplete(
                    (outcome, failure) ->
                            settle(actor, generation, responseTo, ticket, outcome, failure));
        } catch (Throwable failure) {
            ticket.reject(actor, generation, responseTo, failure);
        }
        return ticket.result();
    }

    /**
     * Actor-lane settlement path for the compiler's future unary adapter.
     * Unlike dispatch(...), the final reply ticket is settled ONLY by a
     * mailbox continuation after the current guest turn has unwound.
     *
     * This does NOT yet make arbitrary Java CompletionStage middleware hooks
     * guest-safe. Compiled guest hooks must themselves be resumed through an
     * OresScheduler task rather than CompletionStage.thenCompose on the
     * producer thread; @ActorMiddleware remains fail-closed until then.
     */
    CompletionStage<ActorMiddlewareValuePipeline.Reply<R>> dispatchActorOwned(
            ActorRuntime.ActorContext<?> context,
            ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<R>> ticket,
            ActorMiddlewarePipeline.Envelope request,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(context, "actor context");
        Objects.requireNonNull(ticket, "reply ticket");
        Objects.requireNonNull(request, "request envelope");
        Objects.requireNonNull(cancelled, "cancellation observer");
        ActorRuntime runtime = context.runtime();
        ActorRuntime.ActorRef<?> self = context.self();
        if (ActorRuntime.currentActorRuntime() != runtime
                || !runtime.currentThreadOwnsActorExecutionLease()
                || !ActorRuntime.currentActorId().filter(self.id()::equals).isPresent()) {
            throw new SecurityException(
                    "unary guest reply adapter requires the owning actor execution turn");
        }

        var actor = self.id();
        long generation = self.generation();
        ticket.claimDispatch(actor, generation);
        UUID responseTo = ticket.requestId();

        // A stopped actor cannot run mailbox continuations. Its done barrier
        // must revoke a ticket even if its producer Stage never settles.
        self.getDone().whenCompleteRuntime((ignored, failure) -> ticket.cancel());

        BooleanSupplier cancelledOrRevoked = () -> {
            if (ticket.state() == ActorUnaryReplySlot.State.CANCELLED) return true;
            if (cancelled.getAsBoolean()) {
                ticket.cancel();
                return true;
            }
            return false;
        };
        try {
            if (cancelledOrRevoked.getAsBoolean()) return ticket.result();
            runtime.enqueueOnCompletion(
                    middleware.invoke(request, cancelledOrRevoked),
                    runtime.captureCurrentContinuationTarget(),
                    (outcome, failure) ->
                            settle(actor, generation, responseTo, ticket, outcome, failure));
        } catch (Throwable failure) {
            ticket.reject(actor, generation, responseTo, failure);
        }
        return ticket.result();
    }

    private void settle(
            ActorRuntime.ActorId actor,
            long generation,
            UUID responseTo,
            ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<R>> ticket,
            ActorMiddlewareValuePipeline.Outcome<R> outcome,
            Throwable failure) {
        if (failure != null) {
            ticket.reject(actor, generation, responseTo, failure);
        } else if (outcome instanceof ActorMiddlewareValuePipeline.Reply<?> reply) {
            @SuppressWarnings("unchecked")
            ActorMiddlewareValuePipeline.Reply<R> result =
                    (ActorMiddlewareValuePipeline.Reply<R>) reply;
            ticket.reply(actor, generation, responseTo, result);
        } else if (outcome instanceof ActorMiddlewareValuePipeline.Reject<?> rejection) {
            ticket.reject(actor, generation, responseTo,
                    new RejectedException(rejection.status(), rejection.reason()));
        } else {
            ticket.reject(actor, generation, responseTo,
                    new IllegalStateException("invalid typed middleware result"));
        }
    }
}
