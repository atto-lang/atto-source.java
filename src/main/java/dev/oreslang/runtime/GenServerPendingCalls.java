package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Runtime-only pending GenServer call lifecycle.
 *
 * <p>Reply, timeout, cancellation and registry close all arbitrate through the
 * same {@link GenServerReplyLedger} ticket. No terminal path may settle the
 * Future unless it first wins that one-shot ledger claim. This prevents a late
 * reply from reviving a timed-out/cancelled call and prevents consumer
 * cancellation from defeating a reply that has already won correlation.</p>
 *
 * <p>This type is intentionally package-private. It is not a guest-visible
 * ReplyCapability and does not dispatch actor messages by itself.</p>
 */
final class GenServerPendingCalls<R> implements AutoCloseable {
    static final class PendingCall<R> {
        private final GenServerPendingCalls<R> owner;
        private final GenServerReplyLedger.Ticket ticket;
        private final GenServerReplyLedger.Target target;
        private final OresFuture<R> future;

        private PendingCall(
                GenServerPendingCalls<R> owner,
                GenServerReplyLedger.Ticket ticket,
                GenServerReplyLedger.Target target,
                OresFuture<R> future) {
            this.owner = owner;
            this.ticket = ticket;
            this.target = target;
            this.future = future;
        }

        OresFuture<R> future() {
            return future;
        }
    }

    private final GenServerReplyLedger ledger;
    private final ActorReliabilityKernel reliability;
    private final Map<GenServerReplyLedger.Ticket, PendingCall<R>> active =
            new IdentityHashMap<>();
    private boolean closed;

    GenServerPendingCalls(int capacity) {
        this(capacity, null);
    }

    GenServerPendingCalls(int capacity, ActorReliabilityKernel reliability) {
        this.ledger = new GenServerReplyLedger(capacity);
        this.reliability = reliability;
    }

    PendingCall<R> register(GenServerReplyLedger.Target target) {
        Objects.requireNonNull(target, "target");
        PendingCall<R> call;
        int depth;
        try {
            synchronized (active) {
                if (closed) {
                    throw new GenServerReplyLedger.AdmissionException(
                            GenServerReplyLedger.AdmissionException.Reason.CLOSED,
                            "pending-call registry is closed");
                }
                GenServerReplyLedger.Ticket ticket = ledger.register(target);
                OresFuture<R> future = new OresFuture<>(
                        () -> retireForConsumerCancel(ticket, target),
                        () -> { });
                call = new PendingCall<>(this, ticket, target, future);
                active.put(ticket, call);
                depth = active.size();
            }
        } catch (GenServerReplyLedger.AdmissionException rejected) {
            record(ActorReliabilityKernel.EventKind.OVERLOAD_SHED, target,
                    rejected.reason() == GenServerReplyLedger.AdmissionException.Reason.CAPACITY
                            ? ActorReliabilityKernel.FaultClass.OVERLOAD
                            : ActorReliabilityKernel.FaultClass.SHUTDOWN,
                    pendingCount());
            throw rejected;
        }
        record(ActorReliabilityKernel.EventKind.CALL_REGISTERED, target,
                ActorReliabilityKernel.FaultClass.NONE, depth);
        return call;
    }

    boolean tryReply(
            PendingCall<R> call,
            GenServerReplyLedger.Target respondingActor,
            R value) {
        if (!owns(call)) return false;
        if (!ledger.tryReply(call.ticket, respondingActor)) {
            record(ActorReliabilityKernel.EventKind.CALL_REPLY_REJECTED, respondingActor,
                    ActorReliabilityKernel.FaultClass.STALE_OR_DUPLICATE, pendingCount());
            return false;
        }
        removeActive(call.ticket);
        if (!call.future.completeFromRuntime(value)) {
            throw new IllegalStateException(
                    "GenServer reply won correlation but Future was already terminal");
        }
        record(ActorReliabilityKernel.EventKind.CALL_REPLIED, call.target,
                ActorReliabilityKernel.FaultClass.NONE, pendingCount());
        return true;
    }

    boolean tryTimeout(PendingCall<R> call) {
        if (!owns(call)) return false;
        if (!ledger.retire(call.ticket)) return false;
        removeActive(call.ticket);
        if (!call.future.failFromRuntime(
                new TimeoutException("GenServer call deadline expired"))) {
            throw new IllegalStateException(
                    "GenServer timeout won correlation but Future was already terminal");
        }
        record(ActorReliabilityKernel.EventKind.CALL_TIMEOUT, call.target,
                ActorReliabilityKernel.FaultClass.TIMEOUT, pendingCount());
        return true;
    }

    boolean cancel(PendingCall<R> call) {
        return owns(call) && call.future.cancel(false);
    }

    int pendingCount() {
        synchronized (active) {
            return active.size();
        }
    }

    int closePending(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<PendingCall<R>> toFail = new ArrayList<>();
        synchronized (active) {
            if (closed) return 0;
            closed = true;
            for (GenServerReplyLedger.Ticket ticket : ledger.closeTickets()) {
                PendingCall<R> call = active.remove(ticket);
                if (call != null) toFail.add(call);
            }
        }

        for (PendingCall<R> call : toFail) {
            if (!call.future.failFromRuntime(failure)) {
                throw new IllegalStateException(
                        "GenServer close won correlation but Future was already terminal");
            }
            record(ActorReliabilityKernel.EventKind.CALL_CLOSED, call.target,
                    ActorReliabilityKernel.FaultClass.SHUTDOWN, pendingCount());
        }
        return toFail.size();
    }

    @Override
    public void close() {
        closePending(new IllegalStateException("GenServer pending calls closed"));
    }

    private boolean retireForConsumerCancel(
            GenServerReplyLedger.Ticket ticket,
            GenServerReplyLedger.Target target) {
        boolean won = ledger.retire(ticket);
        if (won) {
            removeActive(ticket);
            record(ActorReliabilityKernel.EventKind.CALL_CANCELLED, target,
                    ActorReliabilityKernel.FaultClass.CANCELLED, pendingCount());
        }
        return won;
    }

    private void removeActive(GenServerReplyLedger.Ticket ticket) {
        synchronized (active) {
            active.remove(ticket);
        }
    }

    private boolean owns(PendingCall<R> call) {
        return call != null && call.owner == this;
    }

    private void record(
            ActorReliabilityKernel.EventKind kind,
            GenServerReplyLedger.Target target,
            ActorReliabilityKernel.FaultClass fault,
            long value) {
        if (reliability == null) return;
        reliability.record(kind,
                new ActorReliabilityKernel.ActorKey(
                        target.runtimeId(), target.actorId(), target.incarnation()),
                fault, value);
    }
}

