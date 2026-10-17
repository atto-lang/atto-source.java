package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Host/CONTROL-only foundation for future typed GenServer call/reply.
 *
 * <p>Tickets are opaque single-use local objects; they MUST NOT be serialized,
 * exposed as guest DATA, or mistaken for supervisor authority. Correlations are
 * deterministic process-local sequence numbers, while runtime/actor/incarnation
 * identity remains the authority fence.</p>
 *
 * <p>No callbacks or guest code are invoked while holding the short ledger lock.</p>
 */
final class GenServerReplyLedger {
    static final class AdmissionException extends IllegalStateException {
        enum Reason { CLOSED, CAPACITY, CORRELATION_EXHAUSTED }
        private final Reason reason;

        AdmissionException(Reason reason, String message) {
            super(message, null);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        Reason reason() { return reason; }
    }

    record Target(UUID runtimeId, UUID actorId, long incarnation) {
        Target {
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) {
                throw new IllegalArgumentException("incarnation must be positive");
            }
        }
    }

    static final class Ticket {
        private final GenServerReplyLedger ledger;
        private final Target target;
        private final long correlation;

        private Ticket(GenServerReplyLedger ledger, Target target, long correlation) {
            this.ledger = ledger;
            this.target = target;
            this.correlation = correlation;
        }

        long correlationForDiagnostics() {
            return correlation;
        }
    }

    record Snapshot(
            int capacity,
            int pending,
            boolean closed,
            long nextCorrelation,
            long registered,
            long replyClaims,
            long retireClaims,
            long rejectedClaims,
            long capacityRejected) { }

    private final int capacity;
    private final Map<Long, Ticket> pending = new HashMap<>();
    private boolean closed;
    /** Monotonic, local, opaque. Zero permanently marks sequence exhaustion. */
    private long nextCorrelation = 1L;
    private long registered;
    private long replyClaims;
    private long retireClaims;
    private long rejectedClaims;
    private long capacityRejected;

    GenServerReplyLedger(int capacity) {
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("capacity must be 1..1,000,000");
        }
        this.capacity = capacity;
    }

    Ticket register(Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (pending) {
            if (closed) {
                throw new AdmissionException(
                        AdmissionException.Reason.CLOSED,
                        "GenServer reply ledger is closed");
            }
            if (pending.size() >= capacity) {
                capacityRejected = saturatingIncrement(capacityRejected);
                throw new AdmissionException(
                        AdmissionException.Reason.CAPACITY,
                        "bounded GenServer call quota exceeded");
            }
            if (nextCorrelation == 0L) {
                throw new AdmissionException(
                        AdmissionException.Reason.CORRELATION_EXHAUSTED,
                        "GenServer correlation sequence exhausted");
            }
            long correlation = nextCorrelation;
            nextCorrelation = correlation == Long.MAX_VALUE
                    ? 0L : correlation + 1L;
            Ticket ticket = new Ticket(this, target, correlation);
            pending.put(correlation, ticket);
            registered = saturatingIncrement(registered);
            return ticket;
        }
    }

    /**
     * True at most once when target and incarnation match and ticket is live.
     * False never settles another request and never proves actor death.
     */
    boolean tryReply(Ticket ticket, Target respondingActor) {
        Objects.requireNonNull(respondingActor, "respondingActor");
        if (ticket == null
                || ticket.ledger != this
                || !ticket.target.equals(respondingActor)) {
            synchronized (pending) {
                rejectedClaims = saturatingIncrement(rejectedClaims);
            }
            return false;
        }
        synchronized (pending) {
            boolean won = pending.remove(ticket.correlation, ticket);
            if (won) replyClaims = saturatingIncrement(replyClaims);
            else rejectedClaims = saturatingIncrement(rejectedClaims);
            return won;
        }
    }

    /** Timeout/cancel retire correlation; accepted server work may still run. */
    boolean retire(Ticket ticket) {
        if (ticket == null || ticket.ledger != this) {
            synchronized (pending) {
                rejectedClaims = saturatingIncrement(rejectedClaims);
            }
            return false;
        }
        synchronized (pending) {
            boolean won = pending.remove(ticket.correlation, ticket);
            if (won) retireClaims = saturatingIncrement(retireClaims);
            else rejectedClaims = saturatingIncrement(rejectedClaims);
            return won;
        }
    }

    int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    Snapshot snapshot() {
        synchronized (pending) {
            return new Snapshot(
                    capacity,
                    pending.size(),
                    closed,
                    nextCorrelation,
                    registered,
                    replyClaims,
                    retireClaims,
                    rejectedClaims,
                    capacityRejected);
        }
    }

    /**
     * Close atomically fences further registrations/replies and returns the
     * exact tickets that lost to shutdown. Caller settlement occurs after lock release.
     */
    List<Ticket> closeTickets() {
        synchronized (pending) {
            if (closed) return List.of();
            closed = true;
            List<Ticket> discarded = List.copyOf(pending.values());
            pending.clear();
            return discarded;
        }
    }

    int close() {
        return closeTickets().size();
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
