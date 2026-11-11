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
        long deliveryTicket;

        Slot(ActorRuntime.ActorId child, long generation) {
            this.child = child;
            this.generation = generation;
        }
    }

    /** Serializes two-lane handovers before acquiring either lane monitor. */
    private static final Object TRANSFER_COORDINATOR = new Object();

    private final int capacity;
    private final Map<ActorRuntime.ActorId, Slot> slots = new HashMap<>();
    private Slot head;
    private Slot tail;
    private int pending;
    private int inFlight;
    private long nextDeliveryTicket;
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

    /**
     * Opaque single-attempt settlement authority. Identity is the exact
     * source lane, Slot object and monotonic poll ticket; never an actor ID
     * or guest-manufacturable generation alone.
     */
    static final class Delivery {
        private final ActorSupervisorControlLane owner;
        private final Slot slot;
        private final long ticket;
        private final ActorRuntime.ActorFailureNotice notice;

        private Delivery(ActorSupervisorControlLane owner, Slot slot, long ticket) {
            this.owner = owner;
            this.slot = slot;
            this.ticket = ticket;
            this.notice = slot.notice;
        }

        ActorRuntime.ActorFailureNotice notice() {
            return notice;
        }
    }

    synchronized Optional<Delivery> poll() {
        if (inFlight != 0) {
            throw new IllegalStateException("overlapping supervisor CONTROL admissions");
        }
        if (head == null) return Optional.empty();
        // Overflow rejects before removing anything from the queue. Reusing
        // a ticket after wraparound would recreate a stale ACK authority.
        long ticket = Math.addExact(nextDeliveryTicket, 1L);
        nextDeliveryTicket = ticket;
        Slot slot = head;
        head = slot.next;
        if (head == null) tail = null;
        slot.next = null;
        slot.queued = false;
        slot.inFlight = true;
        slot.deliveryTicket = ticket;
        inFlight = 1;
        pending--;
        return Optional.of(new Delivery(this, slot, ticket));
    }

    synchronized void ack(Delivery delivery) {
        Slot slot = requiredDelivery(delivery);
        slots.remove(slot.child);
        slot.inFlight = false;
        slot.deliveryTicket = 0L;
        slot.notice = null;
        inFlight = 0;
        acknowledged++;
    }

    synchronized void retry(Delivery delivery) {
        Slot slot = requiredDelivery(delivery);
        slot.inFlight = false;
        slot.deliveryTicket = 0L;
        inFlight = 0;
        retried++;
        enqueue(slot);
    }

    private Slot requiredDelivery(Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        Slot slot = delivery.slot;
        if (delivery.owner != this || slots.get(slot.child) != slot
                || !slot.inFlight || slot.deliveryTicket != delivery.ticket
                || slot.notice != delivery.notice) {
            throw new IllegalStateException(
                    "stale, foreign or already-settled supervisor CONTROL delivery");
        }
        return slot;
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
    void transferPublishedTo(ActorSupervisorControlLane root) {
        Objects.requireNonNull(root, "root");
        if (root == this) {
            throw new IllegalArgumentException("cannot transfer CONTROL to its own lane");
        }

        // Never nest lane monitors without acquiring the shared transfer lock:
        // two concurrent opposite-direction transfers would otherwise ABBA
        // deadlock while publishing into each other's destination.
        synchronized (TRANSFER_COORDINATOR) {
            synchronized (this) {
                synchronized (root) {
                    // A single missing/stale/occupied destination reservation
                    // must reject the *entire* handover before its first
                    // publication. Partial migration could otherwise lose the
                    // parent's ability to account for already-transferred
                    // failures after a later root admission rejection.
                    for (Slot slot : slots.values()) {
                        if (slot.notice == null) continue;
                        Slot reserved = root.required(slot.child, slot.generation);
                        if (reserved.notice != null || reserved.queued || reserved.inFlight) {
                            throw new IllegalStateException(
                                    "root CONTROL reservation already contains a notice for "
                                            + slot.child);
                        }
                    }

                    // A published in-flight notice is still owned until the
                    // destination has accepted the exact generation. The
                    // parent may have at most one such lease.
                    for (var iterator = slots.values().iterator(); iterator.hasNext();) {
                        Slot slot = iterator.next();
                        if (!slot.inFlight) continue;
                        if (!root.publish(slot.notice)) {
                            throw new IllegalStateException(
                                    "duplicate root CONTROL publication for " + slot.child);
                        }
                        iterator.remove();
                        slot.inFlight = false;
                        slot.deliveryTicket = 0L;
                        inFlight = 0;
                        transferred++;
                        break;
                    }

                    // Preserve the parent's FIFO terminal notice order when
                    // moving queued events into the pre-reserved root queue.
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
            }
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
