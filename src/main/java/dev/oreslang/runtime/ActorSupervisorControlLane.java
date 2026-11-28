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

    Optional<Delivery> poll() {
        return pollMapped(delivery -> delivery);
    }

    // Trusted internal receipt factory only. Construct outward receipt before dequeue.
    synchronized <T> Optional<T> pollMapped(java.util.function.Function<Delivery, T> receipt) {
        if (inFlight != 0) {
            throw new IllegalStateException("overlapping supervisor CONTROL admissions");
        }
        if (head == null) return Optional.empty();
        // Overflow rejects before removing anything from the queue. Reusing
        // a ticket after wraparound would recreate a stale ACK authority.
        long ticket = Math.addExact(nextDeliveryTicket, 1L);
        Slot slot = head;
        // Allocate both the opaque delivery lease and its Optional wrapper
        // before removing the notice from the queue. An allocation failure
        // must leave the original CONTROL notice reachable and retryable.
        Optional<T> ready = Optional.of(receipt.apply(new Delivery(this, slot, ticket)));
        nextDeliveryTicket = ticket;
        head = slot.next;
        if (head == null) tail = null;
        slot.next = null;
        slot.queued = false;
        slot.inFlight = true;
        slot.deliveryTicket = ticket;
        inFlight = 1;
        pending--;
        return ready;
    }

    synchronized void ack(Delivery delivery) {
        Slot slot = requiredDelivery(delivery);
        slots.remove(slot.child);
        slot.inFlight = false;
        slot.deliveryTicket = 0L;
        slot.notice = null;
        inFlight = 0;
        acknowledged = saturatingIncrement(acknowledged);
    }

    synchronized void retry(Delivery delivery) {
        Slot slot = requiredDelivery(delivery);
        slot.inFlight = false;
        slot.deliveryTicket = 0L;
        inFlight = 0;
        retried = saturatingIncrement(retried);
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
     * Validate the entire batch before any publication or source mutation.
     * Runtime calls this only from a child lane to its VM root fallback; the
     * root never transfers back into a child. Both monitors are held through
     * validation and commit so root poll/ack cannot observe a partial batch.
     */
    void transferPublishedTo(ActorSupervisorControlLane root) {
        Objects.requireNonNull(root, "root");
        if (root == this) {
            throw new IllegalArgumentException("cannot transfer CONTROL to its own lane");
        }
        synchronized (TRANSFER_COORDINATOR) {
            synchronized (this) {
                transferLocked(root);
            }
        }
    }

    private void transferLocked(ActorSupervisorControlLane root) {
        if (inFlight == 0 && head == null) return;
        synchronized (root) {
            if (inFlight != 0) {
                for (Slot slot : slots.values()) {
                    if (slot.inFlight) {
                        validateTarget(root, slot);
                        break;
                    }
                }
            }
            for (Slot slot = head; slot != null; slot = slot.next) {
                validateTarget(root, slot);
            }

            if (inFlight != 0) {
                for (var iterator = slots.values().iterator(); iterator.hasNext();) {
                    Slot slot = iterator.next();
                    if (slot.inFlight) {
                        root.publish(slot.notice);
                        iterator.remove();
                        slot.inFlight = false;
                        slot.deliveryTicket = 0L;
                        slot.notice = null;
                        inFlight = 0;
                        transferred = saturatingIncrement(transferred);
                        break;
                    }
                }
            }

            while (head != null) {
                Slot slot = head;
                root.publish(slot.notice);
                head = slot.next;
                slot.next = null;
                slot.queued = false;
                pending--;
                slots.remove(slot.child);
                slot.notice = null;
                transferred = saturatingIncrement(transferred);
            }
            tail = null;
        }
    }

    private static void validateTarget(ActorSupervisorControlLane root, Slot source) {
        Slot reserved = root.required(source.child, source.generation);
        if (reserved.notice != null || reserved.queued || reserved.inFlight) {
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
