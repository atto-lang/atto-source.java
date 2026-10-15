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
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            ticket.cancel();
            throw fatal;
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

        // A stopped actor cannot run mailbox continuations. The done
        // barrier revokes an unresolved ticket, but must not indefinitely
        // retain completed request tickets on a long-lived actor.
        OresFuture.RuntimeWaiterRegistration<Void> stopWaiter =
                self.getDone().whenCompleteRuntimeCancellable(
                        (ignored, failure) -> ticket.cancel());
        ticket.result().whenComplete((ignored, failure) -> stopWaiter.detach());

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
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            // Guest-fatal conditions remain fatal for the actor runtime.
            // Actor finalization revokes any outstanding reply ticket.
            throw fatal;
        } catch (RuntimeException failure) {
            ticket.reject(actor, generation, responseTo, failure);
        }
        return ticket.result();
    }

    /**
     * Trusted host-facing typed unary reply handoff. Unlike the actor-local
     * dispatchActorOwned stage, this stage is safe for a caller that may close
     * the Truffle context as soon as the reply becomes visible.
     *
     * Guest middleware remains disabled: middleware.invoke() still allows
     * CompletionStage stage functions to run on foreign producer threads.
     */
    CompletionStage<ActorMiddlewareValuePipeline.Reply<R>> dispatchActorOwnedPostExit(
            ActorRuntime.ActorContext<?> context,
            ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<R>> ticket,
            ActorMiddlewarePipeline.Envelope request,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(context, "actor context");
        ActorRuntime runtime = context.runtime();
        ActorRuntime.ActorRef<?> self = context.self();
        CompletionStage<ActorMiddlewareValuePipeline.Reply<R>> internal =
                dispatchActorOwned(context, ticket, request, cancelled);
        var outward = new java.util.concurrent.CompletableFuture<
                ActorMiddlewareValuePipeline.Reply<R>>();

        // If STOP prevents a pending actor continuation from running, only
        // the finalized owner barrier may publish its cancellation externally.
        OresFuture.RuntimeWaiterRegistration<Void> doneObserver =
                self.getDone().whenCompleteRuntimeCancellable((ignored, failure) ->
                        outward.completeExceptionally(
                                new java.util.concurrent.CancellationException(
                                        "actor terminated before external reply publication")));
        outward.whenComplete((ignored, failure) -> doneObserver.detach());

        runtime.enqueueOnCompletion(
                internal, runtime.captureCurrentContinuationTarget(),
                (reply, failure) -> {
                    final Runnable publish;
                    try {
                        if (failure != null) {
                            Throwable reason = OresFuture.unwrap(failure);
                            publish = () -> outward.completeExceptionally(reason);
                        } else {
                            // A CompletionStage is not a memory-domain boundary.
                            // Freeze *before* leaving the actor context, and
                            // expose no guest-owned mutable value to the caller.
                            @SuppressWarnings("unchecked")
                            R frozen = (R) ActorRuntime.freeze(reply.value());
                            var immutable = new ActorMiddlewareValuePipeline.Reply<>(
                                    frozen, reply.response());
                            publish = () -> {
                                if (!self.isAlive()) {
                                    outward.completeExceptionally(
                                            new java.util.concurrent.CancellationException(
                                                    "actor stopped before external reply publication"));
                                } else {
                                    outward.complete(immutable);
                                }
                            };
                        }
                    } catch (RuntimeException invalidReply) {
                        // Reject unsupported payloads, but only after context
                        // exit; the failed freeze may still refer to guest data.
                        runtime.publishAfterCurrentActorCarrierExit(
                                () -> outward.completeExceptionally(invalidReply));
                        return;
                    }
                    runtime.publishAfterCurrentActorCarrierExit(publish);
                });
        return outward.minimalCompletionStage();
    }

    private void settle(
            ActorRuntime.ActorId actor,
            long generation,
            UUID responseTo,
            ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<R>> ticket,
            ActorMiddlewareValuePipeline.Outcome<R> outcome,
            Throwable failure) {
        if (failure != null) {
            Throwable reason = OresFuture.unwrap(failure);
            if (reason instanceof VirtualMachineError fatal) {
                ticket.cancel();
                throw fatal;
            }
            if (reason instanceof ThreadDeath fatal) {
                ticket.cancel();
                throw fatal;
            }
            if (reason instanceof LinkageError fatal) {
                ticket.cancel();
                throw fatal;
            }
            ticket.reject(actor, generation, responseTo, reason);
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
