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
    private final ActorSupervisorControlLane fallback;
    private final Map<ActorRuntime.ActorId, Slot> slots = new HashMap<>();
    private Slot head;
    private Slot tail;
    private int pending;
    private Slot inFlight;
    private long published;
    private long acknowledged;
    private long retried;
    private long transferred;
    private long reservationRejected;
    private long duplicatePublishRejected;

    ActorSupervisorControlLane(int capacity) {
        this(capacity, null);
    }

    private ActorSupervisorControlLane(int capacity, ActorSupervisorControlLane fallback) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("CONTROL capacity must be positive");
        }
        this.capacity = capacity;
        this.fallback = fallback;
    }

    ActorSupervisorControlLane childLane() {
        if (fallback != null) {
            throw new IllegalStateException("only the root CONTROL lane can supply fallback slots");
        }
        return new ActorSupervisorControlLane(capacity, this);
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
            reservationRejected = saturatingIncrement(reservationRejected);
            throw new RejectedExecutionException(
                    "actor supervisor CONTROL slots exhausted; drain terminal events");
        }
        slots.put(child, new Slot(child, generation));
    }

    synchronized boolean publish(ActorRuntime.ActorFailureNotice notice) {
        Objects.requireNonNull(notice, "notice");
        Slot slot = required(notice.actor(), notice.generation());
        if (slot.notice != null) {
            duplicatePublishRejected = saturatingIncrement(duplicatePublishRejected);
            return false;
        }
        slot.notice = notice;
        enqueue(slot);
        published = saturatingIncrement(published);
        return true;
    }

    synchronized Optional<ActorRuntime.ActorFailureNotice> poll() {
        if (inFlight != null) {
            throw new IllegalStateException("overlapping supervisor CONTROL admissions");
        }
        if (head == null) return Optional.empty();
        Slot slot = head;
        head = slot.next;
        if (head == null) tail = null;
        slot.next = null;
        slot.queued = false;
        slot.inFlight = true;
        inFlight = slot;
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
        inFlight = null;
        acknowledged = saturatingIncrement(acknowledged);
    }

    synchronized void retry(ActorRuntime.ActorId child, long generation) {
        Slot slot = required(child, generation);
        if (!slot.inFlight) {
            throw new IllegalStateException("CONTROL event is not in flight");
        }
        slot.inFlight = false;
        inFlight = null;
        retried = saturatingIncrement(retried);
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
     * Validate the complete transfer before making any notice visible at root.
     */
    synchronized void transferPublishedToRoot() {
        if (fallback == null) {
            throw new IllegalStateException("the root CONTROL lane has no fallback");
        }
        if (inFlight == null && head == null) return;
        // Nested locks always go from a child lane to its fixed root.
        synchronized (fallback) {
            if (inFlight != null) validateFallbackSlot(inFlight);
            for (Slot slot = head; slot != null; slot = slot.next) validateFallbackSlot(slot);

            if (inFlight != null) {
                Slot slot = inFlight;
                fallback.publish(slot.notice);
                slots.remove(slot.child);
                slot.inFlight = false;
                inFlight = null;
                transferred = saturatingIncrement(transferred);
            }

            while (head != null) {
                Slot slot = head;
                fallback.publish(slot.notice);
                head = slot.next;
                slot.next = null;
                slot.queued = false;
                pending--;
                slots.remove(slot.child);
                transferred = saturatingIncrement(transferred);
            }
            tail = null;
        }
    }

    private void validateFallbackSlot(Slot source) {
        Slot reserved = fallback.required(source.child, source.generation);
        if (reserved.notice != null) {
            throw new IllegalStateException(
                    "duplicate root CONTROL publication for " + source.child);
        }
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
                inFlight == null ? 0 : 1,
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

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
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
