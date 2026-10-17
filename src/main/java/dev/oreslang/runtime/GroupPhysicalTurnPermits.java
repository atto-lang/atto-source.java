package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Host-only proof of bounded, group-owned physical turn credits.
 *
 * <p>No actor/user callback runs here. Denied dispatchers must yield their
 * carrier. A successful release returns at most one reserved FIFO successor
 * to the lifecycle-owning scheduler, which must publish it on the existing
 * domain lane or release it again if publication fails.
 *
 * <p>This does not enable non-default ActorGroupPlacementPolicy yet: actual
 * ownership, topology, and dispatcher handoff wiring require separate review.
 */
final class GroupPhysicalTurnPermits {
    record Actor(long id, long incarnation) {
        Actor {
            if (id <= 0 || incarnation <= 0)
                throw new IllegalArgumentException("actor incarnation must be positive");
        }
    }

    record Grant(Actor actor, long ticket) {
        Grant {
            Objects.requireNonNull(actor);
            if (ticket <= 0) throw new IllegalArgumentException("invalid turn ticket");
        }
    }

    enum Admission { GRANTED, QUEUED, DUPLICATE, QUEUE_FULL, RETRY, CLOSED }
    record Request(Admission status, Grant grant) { }
    record Release(boolean released, boolean retry, Grant successor) { }
    enum Cancellation { CANCELLED, ACTIVE, ABSENT, RETRY }
    enum Seal { SEALED, ALREADY_SEALED, RETRY }
    record Snapshot(int active, int queued, boolean sealed) { }

    private final ReentrantLock lock = new ReentrantLock();
    private final int limit;
    private final int queueCapacity;
    private final Map<Actor, Grant> active = new HashMap<>();
    private final ArrayDeque<Actor> queue = new ArrayDeque<>();
    private final Set<Actor> queued = new HashSet<>();
    private long nextTicket = 1;
    private boolean sealed;

    GroupPhysicalTurnPermits(int maxParallelTurns, int maxQueuedActors) {
        if (maxParallelTurns < 1 || maxParallelTurns > 1024)
            throw new IllegalArgumentException("group physical turns must be 1..1024");
        if (maxQueuedActors < 0 || maxQueuedActors > 65536)
            throw new IllegalArgumentException("bounded queue capacity required");
        limit = maxParallelTurns;
        queueCapacity = maxQueuedActors;
    }

    /** Never parks: lock contention returns RETRY to the existing dispatcher. */
    Request tryRequest(Actor actor) {
        Objects.requireNonNull(actor);
        if (!lock.tryLock()) return new Request(Admission.RETRY, null);
        try {
            if (sealed) return new Request(Admission.CLOSED, null);
            if (active.containsKey(actor) || queued.contains(actor))
                return new Request(Admission.DUPLICATE, null);
            if (active.size() < limit && queue.isEmpty()) {
                Grant grant = grantUnderLock(actor);
                return grant == null
                        ? new Request(Admission.CLOSED, null)
                        : new Request(Admission.GRANTED, grant);
            }
            if (queue.size() == queueCapacity)
                return new Request(Admission.QUEUE_FULL, null);
            queued.add(actor);
            queue.addLast(actor);
            return new Request(Admission.QUEUED, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A physical turn has ended; transfer its credit to the oldest waiter.
     * RETRY means the caller STILL owns its grant and must arrange a bounded
     * later attempt. A stale ticket can never free another actor's credit.
     */
    Release tryRelease(Grant grant) {
        Objects.requireNonNull(grant);
        if (!lock.tryLock()) return new Release(false, true, null);
        try {
            Grant held = active.get(grant.actor());
            if (held == null || held.ticket() != grant.ticket())
                return new Release(false, false, null);
            active.remove(grant.actor());
            if (!sealed && !queue.isEmpty()) {
                Actor successor = queue.removeFirst();
                queued.remove(successor);
                Grant next = grantUnderLock(successor);
                return new Release(true, false, next);
            }
            return new Release(true, false, null);
        } finally {
            lock.unlock();
        }
    }

    /** Queued cancellation cannot revoke an already executing physical turn. */
    Cancellation tryCancelQueued(Actor actor) {
        Objects.requireNonNull(actor);
        if (!lock.tryLock()) return Cancellation.RETRY;
        try {
            if (active.containsKey(actor)) return Cancellation.ACTIVE;
            if (!queued.remove(actor)) return Cancellation.ABSENT;
            if (!queue.remove(actor))
                throw new IllegalStateException("queue/index divergence");
            return Cancellation.CANCELLED;
        } finally {
            lock.unlock();
        }
    }

    /** Seals future admission and drops pending (not running) reservations. */
    Seal trySeal() {
        if (!lock.tryLock()) return Seal.RETRY;
        try {
            if (sealed) return Seal.ALREADY_SEALED;
            sealUnderLock();
            return Seal.SEALED;
        } finally {
            lock.unlock();
        }
    }

    Snapshot trySnapshot() {
        if (!lock.tryLock()) return null;
        try {
            return new Snapshot(active.size(), queue.size(), sealed);
        } finally {
            lock.unlock();
        }
    }

    private Grant grantUnderLock(Actor actor) {
        if (nextTicket == Long.MAX_VALUE) {
            // Never recycle a ticket: fail-closed while retaining running
            // credits for their eventual physical exit.
            sealUnderLock();
            return null;
        }
        Grant grant = new Grant(actor, nextTicket++);
        active.put(actor, grant);
        return grant;
    }

    private void sealUnderLock() {
        sealed = true;
        queue.clear();
        queued.clear();
    }
}
