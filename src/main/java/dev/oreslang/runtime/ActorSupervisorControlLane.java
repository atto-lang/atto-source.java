package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, pre-reserved CONTROL transport for terminal child-actor failures.
 *
 * Reservations allocate before child admission; publish only writes into an
 * existing slot and appends an intrusive queue link. No user-mailbox capacity
 * or guest-owned Throwable is involved. Every slot is keyed by a fresh ActorId
 * (nonreused actor generation), so stale/double publication fails closed.
 *
 * All operations are synchronized. Guest callbacks must run OUTSIDE this lock.
 * A polled event stays reserved/in-flight until explicitly acknowledged.
 */
final class ActorSupervisorControlLane {
    private static final class Slot {
        final ActorRuntime.ActorId child;
        ActorRuntime.ActorFailureNotice notice;
        Slot next;
        boolean queued;
        boolean inFlight;

        Slot(ActorRuntime.ActorId child) { this.child = child; }
    }

    private final int capacity;
    private final Map<ActorRuntime.ActorId, Slot> slots = new HashMap<>();
    private Slot head;
    private Slot tail;
    private int pending;

    ActorSupervisorControlLane(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("CONTROL capacity must be positive");
        this.capacity = capacity;
    }

    synchronized void reserve(ActorRuntime.ActorId child) {
        Objects.requireNonNull(child, "child");
        if (slots.containsKey(child)) {
            throw new IllegalStateException("duplicate actor CONTROL generation reservation");
        }
        if (slots.size() == capacity) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "actor supervisor CONTROL slots exhausted; drain terminal events");
        }
        slots.put(child, new Slot(child));
    }

    synchronized boolean publish(ActorRuntime.ActorFailureNotice failure) {
        Objects.requireNonNull(failure, "failure");
        Slot slot = required(failure.actor());
        if (slot.notice != null) return false;
        slot.notice = failure;
        enqueue(slot);
        return true;
    }

    synchronized Optional<ActorRuntime.ActorFailureNotice> poll() {
        if (head == null) return Optional.empty();
        Slot slot = head;
        head = slot.next;
        if (head == null) tail = null;
        slot.next = null;
        slot.queued = false;
        slot.inFlight = true;
        --pending;
        return Optional.of(slot.notice);
    }

    synchronized void ack(ActorRuntime.ActorId child) {
        Slot slot = required(child);
        if (!slot.inFlight) throw new IllegalStateException("CONTROL event is not in flight");
        slots.remove(child);
        slot.inFlight = false;
        slot.notice = null;
    }

    synchronized void retry(ActorRuntime.ActorId child) {
        Slot slot = required(child);
        if (!slot.inFlight) throw new IllegalStateException("CONTROL event is not in flight");
        slot.inFlight = false;
        enqueue(slot);
    }

    synchronized void retireGracefully(ActorRuntime.ActorId child) {
        Slot slot = required(child);
        if (slot.notice != null) {
            throw new IllegalStateException("cannot retire unacknowledged child CONTROL failure");
        }
        slots.remove(child);
    }

    /**
     * Fail-safe parent retirement: collect pending AND in-flight failures
     * without deleting reservations for children still alive. The caller
     * forwards these immutable notices to its pre-reserved root slots.
     */
    synchronized java.util.List<ActorRuntime.ActorFailureNotice> drainPublished() {
        java.util.ArrayList<ActorRuntime.ActorFailureNotice> failures = new java.util.ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.notice != null) {
                failures.add(slot.notice);
                slots.remove(slot.child);
            }
        }
        head = null;
        tail = null;
        pending = 0;
        return failures;
    }

    synchronized int pendingCount() { return pending; }
    synchronized int reservedCount() { return slots.size(); }

    private Slot required(ActorRuntime.ActorId id) {
        Slot slot = slots.get(id);
        if (slot == null) throw new IllegalStateException("unknown or stale CONTROL actor generation " + id);
        return slot;
    }

    private void enqueue(Slot slot) {
        if (slot.notice == null || slot.queued || slot.inFlight) {
            throw new IllegalStateException("duplicate or empty CONTROL enqueue");
        }
        slot.next = null;
        slot.queued = true;
        if (tail == null) head = slot;
        else tail.next = slot;
        tail = slot;
        ++pending;
    }
}
