package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
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
    // Only runtime-owned futures. Notifications always happen outside metadata.
    private final IdentityHashMap<Ticket, OresFuture<Ticket>> admissions = new IdentityHashMap<>();
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
     * Reserve a gate ticket and expose it as a scheduler-awaitable Future.
     * A pending waiter never blocks its current carrier. Cancelling its Future
     * retracts the ticket; an already published owner must instead be released
     * by its SourceTask lexical cleanup frame.
     */
    public OresFuture<Ticket> requestAwaitable() {
        metadata.lock();
        try {
            if (retired) return OresFuture.failed(
                    new IllegalStateException("critical gate retired"));
            boolean immediate = owner == null && waiters.isEmpty();
            if (!immediate && waiters.size() >= MAX_WAITERS) {
                return OresFuture.failed(
                        new IllegalStateException("critical waiter limit exceeded"));
            }
            Ticket ticket = nextTicket();
            OresFuture<Ticket> future = new OresFuture<>(
                    () -> release(ticket));
            if (immediate) {
                owner = ticket;
                // No observer can see this new Future until this call returns.
                future.completeFromRuntime(ticket);
            } else {
                admissions.put(ticket, future);
                waiters.addLast(ticket);
            }
            return future;
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
        Ticket successor;
        OresFuture<Ticket> readiness;
        metadata.lock();
        try {
            validate(ticket);
            if (retired) return Optional.empty();
            if (owner != ticket) {
                waiters.remove(ticket);
                admissions.remove(ticket);
                return Optional.empty();
            }
            owner = waiters.pollFirst();
            successor = owner;
            readiness = successor == null ? null : admissions.remove(successor);
        } finally {
            metadata.unlock();
        }
        // A Future may synchronously notify host subscribers. Never settle
        // one while holding the short gate metadata lock or execute guest code.
        if (readiness != null) readiness.completeFromRuntime(successor);
        return Optional.ofNullable(successor);
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

    /** No logical owner, queued tickets or retained scheduler admission. */
    public boolean quiescent() {
        metadata.lock();
        try {
            return owner == null && waiters.isEmpty() && admissions.isEmpty();
        } finally {
            metadata.unlock();
        }
    }

    /**
     * Atomically fence new admission ONLY if no critical body or queued
     * waiter can still own logical state. Unlike quiescent(); retire(), this
     * cannot race a new request between observation and retirement.
     * A successful call never has waiters to notify under an actor monitor.
     */
    public boolean retireIfQuiescent() {
        metadata.lock();
        try {
            if (retired) return true;
            if (owner != null || !waiters.isEmpty() || !admissions.isEmpty())
                return false;
            retired = true;
            return true;
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
        List<OresFuture<Ticket>> pending;
        metadata.lock();
        try {
            if (retired) return;
            retired = true;
            owner = null;
            waiters.clear();
            pending = new ArrayList<>(admissions.values());
            admissions.clear();
        } finally {
            metadata.unlock();
        }
        // Retire never calls guest code or scheduler continuations under lock.
        for (OresFuture<Ticket> future : pending) {
            future.failFromRuntime(
                    new IllegalStateException("critical gate retired"));
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
