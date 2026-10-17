package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
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
        private final UUID correlation;

        private Ticket(GenServerReplyLedger ledger, Target target, UUID correlation) {
            this.ledger = ledger;
            this.target = target;
            this.correlation = correlation;
        }
    }

    private final int capacity;
    private final Map<UUID, Ticket> pending = new HashMap<>();
    private boolean closed;

    GenServerReplyLedger(int capacity) {
        if (capacity < 1 || capacity > 1_000_000)
            throw new IllegalArgumentException("capacity must be 1..1,000,000");
        this.capacity = capacity;
    }

    Ticket register(Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (pending) {
            if (closed) throw new IllegalStateException("ledger is closed");
            if (pending.size() >= capacity) throw new IllegalStateException("bounded call quota exceeded");
            UUID correlation;
            do { correlation = UUID.randomUUID(); } while (pending.containsKey(correlation));
            Ticket ticket = new Ticket(this, target, correlation);
            pending.put(correlation, ticket);
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
        if (ticket == null || ticket.ledger != this || !ticket.target.equals(respondingActor))
            return false;
        synchronized (pending) {
            return pending.remove(ticket.correlation, ticket);
        }
    }

    /** Timeout/cancel retire correlation; accepted server work may still run. */
    boolean retire(Ticket ticket) {
        if (ticket == null || ticket.ledger != this) return false;
        synchronized (pending) {
            return pending.remove(ticket.correlation, ticket);
        }
    }

    int pendingCount() {
        synchronized (pending) { return pending.size(); }
    }

    /** Close atomically fences further registrations/replies; no callbacks. */
    int close() {
        synchronized (pending) {
            if (closed) return 0;
            closed = true;
            int discarded = pending.size();
            pending.clear();
            return discarded;
        }
    }
}
