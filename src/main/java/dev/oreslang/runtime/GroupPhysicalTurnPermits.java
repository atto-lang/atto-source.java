package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    record Release(boolean released, boolean retry, Grant successor, List<Actor> cancelled) {
        Release {
            cancelled = List.copyOf(Objects.requireNonNull(cancelled, "cancelled"));
        }
    }
    enum Cancellation { CANCELLED, ACTIVE, ABSENT, RETRY }
    enum SealStatus { SEALED, ALREADY_SEALED, RETRY }
    record Seal(SealStatus status, List<Actor> cancelled) {
        Seal {
            cancelled = List.copyOf(Objects.requireNonNull(cancelled, "cancelled"));
        }
    }
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
        if (!lock.tryLock()) return new Release(false, true, null, List.of());
        try {
            Grant held = active.get(grant.actor());
            // Tokens are capabilities, not matching numeric tuples. Never
            // let a forged copy or another group's identical ticket release
            // this group's physical carrier credit.
            if (held != grant)
                return new Release(false, false, null, List.of());
            active.remove(grant.actor());
            if (!sealed && !queue.isEmpty()) {
                if (nextTicket == Long.MAX_VALUE) {
                    List<Actor> cancelled = sealUnderLock();
                    return new Release(true, false, null, cancelled);
                }
                Actor successor = queue.removeFirst();
                queued.remove(successor);
                Grant next = grantUnderLock(successor);
                return new Release(true, false, next, List.of());
            }
            return new Release(true, false, null, List.of());
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
        if (!lock.tryLock()) return new Seal(SealStatus.RETRY, List.of());
        try {
            if (sealed) return new Seal(SealStatus.ALREADY_SEALED, List.of());
            return new Seal(SealStatus.SEALED, sealUnderLock());
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
            // Never recycle a ticket. An immediate request can fail closed
            // without a waiter; release-to-successor handles queued actors
            // explicitly so lifecycle owners can cancel/reschedule them.
            sealed = true;
            return null;
        }
        Grant grant = new Grant(actor, nextTicket++);
        active.put(actor, grant);
        return grant;
    }

    private List<Actor> sealUnderLock() {
        sealed = true;
        List<Actor> cancelled = List.copyOf(queue);
        queue.clear();
        queued.clear();
        return cancelled;
    }
}
