package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A separately accounted CONTROL lane for terminal actor failures.
 *
 * <p>One slot is reserved for every supervised child <em>before</em> it starts.
 * Therefore an ordinary/user mailbox at capacity cannot consume a failure slot,
 * and terminal failure publication is bounded by the number of admitted children.
 * No guest callback runs on the producer's carrier. The parent/supervisor must
 * poll this lane ahead of user mail at its next scheduler admission.
 *
 * <p>This is a host substrate, not yet an Oreslang guest ActorGroup lowering.
 */
public final class SupervisorFailureLane {
    public record ActorKey(String id, long generation) {
        public ActorKey {
            Objects.requireNonNull(id, "id");
            if (id.isBlank() || generation < 0) {
                throw new IllegalArgumentException("invalid actor identity/generation");
            }
        }
    }

    public enum Kind { THROW, RAISE, PANIC, CANCEL, HOST_FAILURE }

    public record Failure(ActorKey actor, Kind kind, String summary) {
        public Failure {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(summary, "summary");
            // Control-plane event size must be bounded, including hostile guest errors.
            if (summary.length() > 512) summary = summary.substring(0, 512);
        }
    }

    /**
     * Deliberately has no default methods: host supervisors must cover all five
     * failure cases. This is NOT yet a compiler proof for guest on_panic hooks.
     */
    public interface Handler {
        void onThrow(Failure failure);
        void onRaise(Failure failure);
        void onPanic(Failure failure);
        void onCancel(Failure failure);
        void onHostFailure(Failure failure);
    }

    private static final class Slot {
        Failure failure;
        boolean inFlight;
    }

    private final int capacity;
    private final Map<ActorKey, Slot> children = new HashMap<>();
    private final ArrayDeque<ActorKey> pending = new ArrayDeque<>();

    public SupervisorFailureLane(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Must happen before child admission; quota exhaustion denies child spawn. */
    public synchronized void reserve(ActorKey child) {
        Objects.requireNonNull(child, "child");
        if (children.containsKey(child)) {
            throw new IllegalStateException("supervision reservation already exists");
        }
        if (children.size() >= capacity) {
            throw new IllegalStateException("supervision slots exhausted: deny child spawn");
        }
        children.put(child, new Slot());
    }

    /** Exactly one terminal report per generation; duplicates never queue twice. */
    public synchronized boolean publish(Failure failure) {
        Objects.requireNonNull(failure, "failure");
        Slot slot = requireSlot(failure.actor());
        if (slot.failure != null) return false;
        slot.failure = failure;
        pending.addLast(failure.actor());
        return true;
    }

    /**
     * Nonblocking CONTROL dequeue. In-flight delivery keeps the reservation
     * until the supervisor acknowledges it; a crashed consumer may retry.
     */
    public synchronized Optional<Failure> poll() {
        ActorKey key = pending.pollFirst();
        if (key == null) return Optional.empty();
        Slot slot = requireSlot(key);
        if (slot.inFlight || slot.failure == null) {
            throw new IllegalStateException("supervisor queue invariant violated");
        }
        slot.inFlight = true;
        return Optional.of(slot.failure);
    }

    public synchronized void ack(ActorKey child) {
        Slot slot = requireSlot(child);
        if (!slot.inFlight) throw new IllegalStateException("failure has not been delivered");
        children.remove(child);
    }

    /** A failed handler must requeue the event; it must never disappear. */
    public synchronized void retry(ActorKey child) {
        Slot slot = requireSlot(child);
        if (!slot.inFlight) throw new IllegalStateException("failure has not been delivered");
        slot.inFlight = false;
        pending.addLast(child);
    }

    /** Graceful, already-observed completion may release an unused slot. */
    public synchronized void retireGracefully(ActorKey child) {
        Slot slot = requireSlot(child);
        if (slot.failure != null) {
            throw new IllegalStateException("terminal failure requires supervisor acknowledgement");
        }
        children.remove(child);
    }

    /** Executes no callbacks while holding a queue lock. */
    public boolean dispatchOne(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        Optional<Failure> next = poll();
        if (next.isEmpty()) return false;
        Failure failure = next.orElseThrow();
        try {
            switch (failure.kind()) {
                case THROW -> handler.onThrow(failure);
                case RAISE -> handler.onRaise(failure);
                case PANIC -> handler.onPanic(failure);
                case CANCEL -> handler.onCancel(failure);
                case HOST_FAILURE -> handler.onHostFailure(failure);
            }
            ack(failure.actor());
        } catch (RuntimeException | Error failedHandler) {
            retry(failure.actor());
            throw failedHandler;
        }
        return true;
    }

    public synchronized int reservedCount() { return children.size(); }
    public synchronized int pendingCount() { return pending.size(); }

    private Slot requireSlot(ActorKey child) {
        Objects.requireNonNull(child, "child");
        Slot slot = children.get(child);
        if (slot == null) throw new IllegalStateException("unknown or stale actor generation");
        return slot;
    }
}
