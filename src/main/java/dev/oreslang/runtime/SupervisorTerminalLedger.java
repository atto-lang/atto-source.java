package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-reserved durable terminal state for one supervisor domain.
 *
 * <p>Every managed actor reserves one slot before lifecycle ownership is
 * published. Terminal publication then cannot fail because a notification
 * queue is full. A slow supervisor backpressures future actor admission
 * instead of losing an already-admitted actor's terminal state.</p>
 */
final class SupervisorTerminalLedger {
    record Record(long sequence, SupervisorFailureControl.Notice notice) {
        Record {
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            Objects.requireNonNull(notice);
        }
    }

    private static final class Slot {
        final ActorRuntime.ActorId actorId;
        long sequence;
        SupervisorFailureControl.Notice notice;
        boolean inFlight;

        Slot(ActorRuntime.ActorId actorId) {
            this.actorId = Objects.requireNonNull(actorId);
        }
    }

    private final int capacity;
    private final Map<ActorRuntime.ActorId, Slot> slots = new LinkedHashMap<>();
    private final TreeMap<Long, Slot> published = new TreeMap<>();
    private final AtomicLong nextSequence = new AtomicLong();

    SupervisorTerminalLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized void reserve(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId);
        if (slots.containsKey(actorId)) {
            throw new IllegalStateException("actor already has a terminal CONTROL reservation");
        }
        if (slots.size() >= capacity) {
            throw new IllegalStateException(
                    "terminal CONTROL reservations exhausted: maximum " + capacity);
        }
        slots.put(actorId, new Slot(actorId));
    }

    synchronized void releaseUnpublished(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId);
        Slot slot = slots.get(actorId);
        if (slot == null) return;
        if (slot.notice != null) {
            throw new IllegalStateException(
                    "cannot release an unacknowledged terminal CONTROL record");
        }
        slots.remove(actorId);
    }

    synchronized Record publish(SupervisorFailureControl.Notice notice) {
        Objects.requireNonNull(notice);
        Slot slot = slots.get(notice.actorId());
        if (slot == null) {
            throw new IllegalStateException(
                    "terminal CONTROL publication without a pre-reserved actor slot");
        }
        if (slot.notice != null) {
            throw new IllegalStateException("duplicate terminal CONTROL publication");
        }
        long sequence = nextSequence.incrementAndGet();
        slot.sequence = sequence;
        slot.notice = notice;
        published.put(sequence, slot);
        return new Record(sequence, notice);
    }

    synchronized Record leaseFirst() {
        Map.Entry<Long, Slot> first = published.firstEntry();
        if (first == null) return null;
        Slot slot = first.getValue();
        if (slot.inFlight) {
            throw new IllegalStateException("terminal CONTROL record already in flight");
        }
        slot.inFlight = true;
        return new Record(first.getKey(), slot.notice);
    }

    synchronized void retry(long sequence, SupervisorFailureControl.Notice expected) {
        Slot slot = published.get(sequence);
        if (slot == null || slot.notice != expected || !slot.inFlight) {
            throw new IllegalStateException("terminal CONTROL delivery lease mismatch");
        }
        slot.inFlight = false;
    }

    synchronized void acknowledge(long sequence, SupervisorFailureControl.Notice expected) {
        Slot slot = published.get(sequence);
        if (slot == null || slot.notice != expected || !slot.inFlight) {
            throw new IllegalStateException("terminal CONTROL acknowledgement lease mismatch");
        }
        slot.inFlight = false;
        published.remove(sequence);
        slots.remove(slot.actorId, slot);
    }

    synchronized Record pollFirst() {
        Record record = leaseFirst();
        if (record == null) return null;
        acknowledge(record.sequence(), record.notice());
        return record;
    }

    synchronized List<Record> snapshot() {
        List<Record> result = new ArrayList<>(published.size());
        for (Map.Entry<Long, Slot> entry : published.entrySet()) {
            result.add(new Record(entry.getKey(), entry.getValue().notice));
        }
        return List.copyOf(result);
    }

    synchronized int reserved() { return slots.size(); }
    synchronized int published() { return published.size(); }
    synchronized int available() { return capacity - slots.size(); }
    int capacity() { return capacity; }
    long lastSequence() { return nextSequence.get(); }
}
