package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Host/CONTROL-only foundation for future typed GenServer call/reply.
 *
 * Tickets are opaque single-use local objects; they MUST NOT be serialized,
 * exposed as guest DATA, or mistaken for supervisor authority. This is only
 * correlation admission, not a guest GenServer or transport implementation.
 * No callbacks or guest code are invoked while holding the short ledger lock.
 */
final class GenServerReplyLedger {
    record Target(UUID runtimeId, UUID actorId, long incarnation) {
        Target {
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
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
    /** Monotonic, process-local, never serialized. Zero means permanently exhausted. */
    private long nextCorrelation = 1L;
    private long registered;
    private long replyClaims;
    private long retireClaims;
    private long rejectedClaims;
    private long capacityRejected;

    GenServerReplyLedger(int capacity) {
        if (capacity < 1 || capacity > 1_000_000)
            throw new IllegalArgumentException("capacity must be 1..1,000,000");
        this.capacity = capacity;
    }

    Ticket register(Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (pending) {
            if (closed) throw new IllegalStateException("ledger is closed");
            if (pending.size() >= capacity) {
                capacityRejected++;
                throw new IllegalStateException("bounded call quota exceeded");
            }
            long correlation = nextCorrelation;
            if (correlation <= 0) {
                throw new IllegalStateException("GenServer correlation sequence exhausted");
            }
            nextCorrelation = correlation == Long.MAX_VALUE ? 0L : correlation + 1L;
            Ticket ticket = new Ticket(this, target, correlation);
            pending.put(correlation, ticket);
            registered++;
            return ticket;
        }
    }

    /**
     * True at most once when target and incarnation match and ticket is live.
     * Caller separately publishes the reply to a runtime-owned Future.
     * False never settles another request and never proves actor death.
     */
    boolean tryReply(Ticket ticket, Target respondingActor) {
        Objects.requireNonNull(respondingActor, "respondingActor");
        if (ticket == null || ticket.ledger != this || !ticket.target.equals(respondingActor)) {
            synchronized (pending) { rejectedClaims++; }
            return false;
        }
        synchronized (pending) {
            boolean won = pending.remove(ticket.correlation, ticket);
            if (won) replyClaims++;
            else rejectedClaims++;
            return won;
        }
    }

    /** Timeout/cancel retire correlation; accepted server work may still run. */
    boolean retire(Ticket ticket) {
        if (ticket == null || ticket.ledger != this) {
            synchronized (pending) { rejectedClaims++; }
            return false;
        }
        synchronized (pending) {
            boolean won = pending.remove(ticket.correlation, ticket);
            if (won) retireClaims++;
            else rejectedClaims++;
            return won;
        }
    }

    int pendingCount() {
        synchronized (pending) { return pending.size(); }
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
     * exact tickets that lost to shutdown. The caller may settle associated
     * runtime-owned Futures only after this method releases the ledger lock.
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

    /** Close atomically fences further registrations/replies; no callbacks. */
    int close() {
        return closeTickets().size();
    }
}
