package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scheduler-facing capacity-one admission gate for non-suspending Oreslang
 * critical bodies. This class NEVER executes guest code or a continuation.
 *
 * A ticket is not a Java monitor/OS mutex. A queued ticket must be parked by
 * the scheduler; after release, the scheduler enqueues the returned ticket
 * only after checking owner/task/actor generation and cancellation. Entering
 * the body requires a fresh holds(ticket) check.
 *
 * The internal ReentrantLock protects only bounded bookkeeping operations.
 * It MUST NOT remain held while any critical body executes. tryEnter() does
 * not wait even for this internal bookkeeping mutex.
 *
 * This is a runtime substrate, not yet Oreslang critical {} AST lowering.
 */
public final class CriticalGate {
    public static final int MAX_WAITERS = 8_192;

    /** Identity-only unforgeable admission capability; never serialize. */
    public static final class Ticket {
        private final CriticalGate gate;
        private final long sequence;

        private Ticket(CriticalGate gate, long sequence) {
            this.gate = gate;
            this.sequence = sequence;
        }

        public long sequence() {
            return sequence;
        }
    }

    public enum Admission { ACQUIRED, QUEUED }

    public record Request(Ticket ticket, Admission admission) { }

    private final ReentrantLock metadata = new ReentrantLock();
    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private Ticket owner;
    private long issued;
    private boolean retired;

    /** Fast path for try critical: never waits and never registers a waiter. */
    public Optional<Ticket> tryEnter() {
        if (!metadata.tryLock()) return Optional.empty();
        try {
            if (retired || owner != null || !waiters.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(owner = nextTicket());
        } finally {
            metadata.unlock();
        }
    }

    /**
     * Logical request. May return QUEUED but never waits for the active body
     * and never executes arbitrary guest code while under metadata protection.
     */
    public Request request() {
        metadata.lock();
        try {
            if (retired) throw new IllegalStateException("critical gate retired");
            boolean immediate = owner == null && waiters.isEmpty();
            if (!immediate && waiters.size() >= MAX_WAITERS) {
                throw new IllegalStateException("critical waiter limit exceeded");
            }
            Ticket ticket = nextTicket();
            if (immediate) {
                owner = ticket;
                return new Request(ticket, Admission.ACQUIRED);
            }
            waiters.addLast(ticket);
            return new Request(ticket, Admission.QUEUED);
        } finally {
            metadata.unlock();
        }
    }

    /**
     * Either releases the owner and returns the FIFO successor, or retracts a
     * queued waiter. A stale or duplicate release has no effect.
     *
     * Caller must enqueue the successor on the authenticated scheduler OUTSIDE
     * this call; a returned ticket cannot by itself execute a continuation.
     */
    public Optional<Ticket> release(Ticket ticket) {
        metadata.lock();
        try {
            validate(ticket);
            if (retired) return Optional.empty();
            if (owner != ticket) {
                waiters.remove(ticket);
                return Optional.empty();
            }
            owner = waiters.pollFirst();
            return Optional.ofNullable(owner);
        } finally {
            metadata.unlock();
        }
    }

    /** Recheck before entering the body; revalidate actor generation outside. */
    public boolean holds(Ticket ticket) {
        metadata.lock();
        try {
            return !retired && ticket != null && ticket.gate == this && owner == ticket;
        } finally {
            metadata.unlock();
        }
    }

    public int waiting() {
        metadata.lock();
        try {
            return waiters.size();
        } finally {
            metadata.unlock();
        }
    }

    /**
     * Invalidate old-generation tickets at actor/isolate retirement.
     * The runtime MUST first quiesce the physically executing critical body;
     * revocation does not preempt arbitrary mutations or provide rollback.
     */
    public void retire() {
        metadata.lock();
        try {
            retired = true;
            owner = null;
            waiters.clear();
        } finally {
            metadata.unlock();
        }
    }

    public boolean retired() {
        metadata.lock();
        try {
            return retired;
        } finally {
            metadata.unlock();
        }
    }

    private Ticket nextTicket() {
        if (issued == Long.MAX_VALUE)
            throw new IllegalStateException("critical ticket sequence exhausted");
        return new Ticket(this, ++issued);
    }

    private void validate(Ticket ticket) {
        Objects.requireNonNull(ticket, "critical ticket");
        if (ticket.gate != this || ticket.sequence <= 0 || ticket.sequence > issued)
            throw new IllegalArgumentException("foreign or invalid critical ticket");
    }
}
