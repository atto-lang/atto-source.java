package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded, generation-fenced CONTROL transport for child terminal failures.
 *
 * <p>Slots are reserved before child admission. Publication therefore never
 * borrows ordinary actor-mailbox capacity and never allocates queue capacity
 * after a child has already failed. A polled event remains reserved/in-flight
 * until ack/retry/transfer, so supervisor overload cannot silently drop a
 * terminal notice.</p>
 *
 * <p>Guest callbacks must always run outside this monitor.</p>
 */
final class ActorSupervisorControlLane {
    record Snapshot(
            int capacity,
            int reserved,
            int pending,
            int inFlight,
            long published,
            long acknowledged,
            long retried,
            long transferred,
            long reservationRejected,
            long duplicatePublishRejected) { }

    private static final class Slot {
        final ActorRuntime.ActorId child;
        final long generation;
        ActorRuntime.ActorFailureNotice notice;
        Slot next;
        boolean queued;
        boolean inFlight;

        Slot(ActorRuntime.ActorId child, long generation) {
            this.child = child;
            this.generation = generation;
        }
    }

    private final int capacity;
    private final Map<ActorRuntime.ActorId, Slot> slots = new HashMap<>();
    private Slot head;
    private Slot tail;
    private int pending;
    private int inFlight;
    private long published;
    private long acknowledged;
    private long retried;
    private long transferred;
    private long reservationRejected;
    private long duplicatePublishRejected;

    ActorSupervisorControlLane(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("CONTROL capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized void reserve(ActorRuntime.ActorId child, long generation) {
        Objects.requireNonNull(child, "child");
        if (generation <= 0) {
            throw new IllegalArgumentException("CONTROL generation must be positive");
        }
        if (slots.containsKey(child)) {
            throw new IllegalStateException("duplicate actor CONTROL reservation");
        }
        if (slots.size() == capacity) {
            reservationRejected++;
            throw new RejectedExecutionException(
                    "actor supervisor CONTROL slots exhausted; drain terminal events");
        }
        slots.put(child, new Slot(child, generation));
    }

    synchronized boolean publish(ActorRuntime.ActorFailureNotice notice) {
        Objects.requireNonNull(notice, "notice");
        Slot slot = required(notice.actor(), notice.generation());
        if (slot.notice != null) {
            duplicatePublishRejected++;
            return false;
        }
        slot.notice = notice;
        enqueue(slot);
        published++;
        return true;
    }

    synchronized Optional<ActorRuntime.ActorFailureNotice> poll() {
        if (inFlight != 0) {
            throw new IllegalStateException("overlapping supervisor CONTROL admissions");
        }
        if (head == null) return Optional.empty();
        Slot slot = head;
        head = slot.next;
        if (head == null) tail = null;
        slot.next = null;
        slot.queued = false;
        slot.inFlight = true;
        inFlight = 1;
        pending--;
        return Optional.of(slot.notice);
    }

    synchronized void ack(ActorRuntime.ActorId child, long generation) {
        Slot slot = required(child, generation);
        if (!slot.inFlight) {
            throw new IllegalStateException("CONTROL event is not in flight");
        }
        slots.remove(child);
        slot.inFlight = false;
        slot.notice = null;
        inFlight = 0;
        acknowledged++;
    }

    synchronized void retry(ActorRuntime.ActorId child, long generation) {
        Slot slot = required(child, generation);
        if (!slot.inFlight) {
            throw new IllegalStateException("CONTROL event is not in flight");
        }
        slot.inFlight = false;
        inFlight = 0;
        retried++;
        enqueue(slot);
    }

    synchronized void retireGracefully(ActorRuntime.ActorId child, long generation) {
        Slot slot = required(child, generation);
        if (slot.notice != null || slot.queued || slot.inFlight) {
            throw new IllegalStateException(
                    "cannot retire unacknowledged child CONTROL failure");
        }
        slots.remove(child);
    }

    /**
     * Transfer every published event to pre-reserved root slots. Unpublished
     * reservations stay local because their child generations are still alive.
     * Failed target publication leaves the source slot untouched.
     */
    synchronized void transferPublishedTo(ActorSupervisorControlLane root) {
        Objects.requireNonNull(root, "root");
        if (root == this) {
            throw new IllegalArgumentException("cannot transfer CONTROL to its own lane");
        }

        for (var iterator = slots.values().iterator(); iterator.hasNext();) {
            Slot slot = iterator.next();
            if (slot.inFlight) {
                if (!root.publish(slot.notice)) {
                    throw new IllegalStateException(
                            "duplicate root CONTROL publication for " + slot.child);
                }
                iterator.remove();
                slot.inFlight = false;
                inFlight = 0;
                transferred++;
                break; // single-flight invariant means at most one
            }
        }

        while (head != null) {
            Slot slot = head;
            if (!root.publish(slot.notice)) {
                throw new IllegalStateException(
                        "duplicate root CONTROL publication for " + slot.child);
            }
            head = slot.next;
            slot.next = null;
            slot.queued = false;
            pending--;
            slots.remove(slot.child);
            transferred++;
        }
        tail = null;
    }

    synchronized int pendingCount() {
        return pending;
    }

    synchronized boolean hasPending() {
        return pending != 0;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                capacity,
                slots.size(),
                pending,
                inFlight,
                published,
                acknowledged,
                retried,
                transferred,
                reservationRejected,
                duplicatePublishRejected);
    }

    private Slot required(ActorRuntime.ActorId id, long generation) {
        Slot slot = slots.get(id);
        if (slot == null || slot.generation != generation) {
            throw new IllegalStateException(
                    "unknown or stale CONTROL actor generation " + id + "#" + generation);
        }
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
        pending++;
    }
}
