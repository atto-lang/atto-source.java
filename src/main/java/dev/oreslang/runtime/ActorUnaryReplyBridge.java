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
        ticket.requireAdmission(actor, generation);
        UUID responseTo = ticket.requestId();
        try {
            middleware.invoke(request, cancelled).whenComplete((outcome, failure) -> {
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
            });
        } catch (Throwable failure) {
            ticket.reject(actor, generation, responseTo, failure);
        }
        return ticket.result();
    }
}
