package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Lossless bounded terminal CONTROL ledger for supervisor-managed actors.
 *
 * <p>A supervisor reserves one slot <em>before</em> admitting a child
 * incarnation. That reservation guarantees space for exactly one terminal
 * receipt independent of user mailbox occupancy. A published receipt remains
 * at the ordered head until explicitly acknowledged; if supervisor policy
 * crashes before commit, a replacement supervisor can observe the same receipt
 * again. Capacity exhaustion rejects new child/restart admission instead of
 * dropping terminal CONTROL.</p>
 *
 * <p>The ledger owns no threads, timers, actor heaps or exception strings. It
 * never executes callbacks. Reservation and acknowledgement are local opaque
 * capabilities and are not guest DATA.</p>
 */
public final class SupervisorTerminalLedger {
    public enum Kind {
        GRACEFUL,
        CANCELLED,
        STARTUP_FAILED,
        FAILURE,
        QUOTA_EXCEEDED
    }

    public static final class Reservation {
        private final SupervisorTerminalLedger owner;
        private final int slot;
        private final long epoch;
        private final ActorRuntime.ActorId actorId;
        private final long incarnation;

        private Reservation(
                SupervisorTerminalLedger owner,
                int slot,
                long epoch,
                ActorRuntime.ActorId actorId,
                long incarnation) {
            this.owner = owner;
            this.slot = slot;
            this.epoch = epoch;
            this.actorId = actorId;
            this.incarnation = incarnation;
        }

        public ActorRuntime.ActorId actorId() { return actorId; }
        public long incarnation() { return incarnation; }
    }

    public static final class Receipt {
        private final SupervisorTerminalLedger owner;
        private final int slot;
        private final long epoch;
        private final ActorRuntime.ActorId actorId;
        private final long incarnation;
        private final Kind kind;
        private final long sequence;

        private Receipt(
                SupervisorTerminalLedger owner,
                int slot,
                long epoch,
                ActorRuntime.ActorId actorId,
                long incarnation,
                Kind kind,
                long sequence) {
            this.owner = owner;
            this.slot = slot;
            this.epoch = epoch;
            this.actorId = actorId;
            this.incarnation = incarnation;
            this.kind = kind;
            this.sequence = sequence;
        }

        public ActorRuntime.ActorId actorId() { return actorId; }
        public long incarnation() { return incarnation; }
        public Kind kind() { return kind; }
        public long sequence() { return sequence; }

        @Override
        public String toString() {
            return "SupervisorTerminalReceipt[actor=" + actorId.value()
                    + ",incarnation=" + incarnation
                    + ",kind=" + kind
                    + ",sequence=" + sequence + "]";
        }
    }

    public record Stats(
            int capacity,
            int inUse,
            int ready,
            long published,
            long acknowledged,
            long releasedBeforeTerminal) {
        public Stats {
            if (capacity < 1
                    || inUse < 0
                    || ready < 0
                    || inUse > capacity
                    || ready > inUse
                    || published < 0
                    || acknowledged < 0
                    || releasedBeforeTerminal < 0) {
                throw new IllegalArgumentException("invalid supervisor terminal ledger stats");
            }
        }
    }

    private static final byte FREE = 0;
    private static final byte RESERVED = 1;
    private static final byte READY = 2;

    private static final class Slot {
        byte state = FREE;
        long epoch;
        ActorRuntime.ActorId actorId;
        long incarnation;
        Receipt receipt;
    }

    private final Slot[] slots;
    private final int[] free;
    private final int[] ready;
    private int freeCount;
    private int readyHead;
    private int readyCount;
    private long nextReceiptSequence;
    private long published;
    private long acknowledged;
    private long releasedBeforeTerminal;

    public SupervisorTerminalLedger(int capacity) {
        if (capacity < 1 || capacity > 65_536) {
            throw new IllegalArgumentException("capacity must be 1..65536");
        }
        slots = new Slot[capacity];
        free = new int[capacity];
        ready = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new Slot();
            free[i] = capacity - 1 - i;
        }
        freeCount = capacity;
    }

    /**
     * Reserve guaranteed terminal CONTROL capacity for one actor incarnation.
     * No actor should be started until this succeeds.
     */
    public synchronized Reservation reserve(
            ActorRuntime.ActorId actorId,
            long incarnation) {
        Objects.requireNonNull(actorId, "actorId");
        if (incarnation <= 0) {
            throw new IllegalArgumentException("incarnation must be positive");
        }
        if (freeCount == 0) {
            throw new IllegalStateException(
                    "supervisor terminal CONTROL capacity exhausted");
        }

        int index = free[--freeCount];
        Slot slot = slots[index];
        if (slot.state != FREE || slot.receipt != null) {
            throw new IllegalStateException("terminal ledger free-list corruption");
        }
        if (slot.epoch == Long.MAX_VALUE) {
            // Put the slot back; an ambiguous epoch must never be reused.
            free[freeCount++] = index;
            throw new IllegalStateException("terminal reservation epoch exhausted");
        }

        long epoch = ++slot.epoch;
        slot.state = RESERVED;
        slot.actorId = actorId;
        slot.incarnation = incarnation;
        return new Reservation(this, index, epoch, actorId, incarnation);
    }

    /**
     * Publish the one terminal outcome promised by a reservation.
     * This cannot fail because of queue capacity: the slot was prepaid.
     */
    public synchronized void publish(Reservation reservation, Kind kind) {
        Slot slot = validateReservation(reservation);
        Objects.requireNonNull(kind, "kind");
        if (slot.state != RESERVED) {
            throw new IllegalStateException(
                    "terminal reservation was already published or released");
        }
        if (readyCount >= ready.length) {
            throw new IllegalStateException(
                    "terminal ledger invariant violated: prepaid ready queue overflow");
        }
        if (nextReceiptSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("terminal receipt sequence exhausted");
        }

        long sequence = ++nextReceiptSequence;
        Receipt receipt = new Receipt(
                this,
                reservation.slot,
                reservation.epoch,
                reservation.actorId,
                reservation.incarnation,
                kind,
                sequence);
        slot.receipt = receipt;
        slot.state = READY;
        ready[(readyHead + readyCount) % ready.length] = reservation.slot;
        readyCount++;
        published = saturatingIncrement(published);
    }

    /**
     * Read the oldest terminal receipt without consuming it.
     * Repeated peeks return the same receipt until ack().
     */
    public synchronized Optional<Receipt> peek() {
        if (readyCount == 0) return Optional.empty();
        Slot slot = slots[ready[readyHead]];
        if (slot.state != READY || slot.receipt == null) {
            throw new IllegalStateException("terminal ledger ready-queue corruption");
        }
        return Optional.of(slot.receipt);
    }

    /**
     * Commit processing of exactly the ordered head receipt and release its
     * capacity. Out-of-order or foreign acknowledgement fails closed.
     */
    public synchronized void ack(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.owner != this) {
            throw new SecurityException("terminal receipt belongs to another ledger");
        }
        if (readyCount == 0) {
            throw new IllegalStateException("no terminal receipt is awaiting acknowledgement");
        }

        int index = ready[readyHead];
        Slot slot = slots[index];
        if (receipt.slot != index
                || slot.state != READY
                || slot.epoch != receipt.epoch
                || slot.receipt != receipt) {
            throw new IllegalStateException(
                    "terminal receipts must be acknowledged once and in order");
        }

        ready[readyHead] = 0;
        readyHead = (readyHead + 1) % ready.length;
        readyCount--;
        acknowledged = saturatingIncrement(acknowledged);
        freeSlot(index);
    }

    /**
     * Release a reservation only when child admission failed before a terminal
     * actor incarnation existed. Published terminal receipts cannot be erased.
     */
    public synchronized void releaseUnstarted(Reservation reservation) {
        Slot slot = validateReservation(reservation);
        if (slot.state != RESERVED) {
            throw new IllegalStateException(
                    "published terminal CONTROL cannot be released without acknowledgement");
        }
        releasedBeforeTerminal = saturatingIncrement(releasedBeforeTerminal);
        freeSlot(reservation.slot);
    }

    public synchronized Stats stats() {
        return new Stats(
                slots.length,
                slots.length - freeCount,
                readyCount,
                published,
                acknowledged,
                releasedBeforeTerminal);
    }

    private Slot validateReservation(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (reservation.owner != this) {
            throw new SecurityException("terminal reservation belongs to another ledger");
        }
        if (reservation.slot < 0 || reservation.slot >= slots.length) {
            throw new SecurityException("invalid terminal reservation slot");
        }
        Slot slot = slots[reservation.slot];
        if (slot.epoch != reservation.epoch
                || !Objects.equals(slot.actorId, reservation.actorId)
                || slot.incarnation != reservation.incarnation) {
            throw new SecurityException("stale terminal reservation");
        }
        return slot;
    }

    private void freeSlot(int index) {
        Slot slot = slots[index];
        slot.state = FREE;
        slot.actorId = null;
        slot.incarnation = 0L;
        slot.receipt = null;
        if (freeCount >= free.length) {
            throw new IllegalStateException("terminal ledger free-list overflow");
        }
        free[freeCount++] = index;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
