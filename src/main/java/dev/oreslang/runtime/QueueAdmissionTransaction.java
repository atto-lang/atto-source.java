package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-only bounded admission ledger for an actor-owned message queue.
 *
 * <p>ActorRuntime is the only intended admission caller: it authenticates the
 * active mailbox sender and invokes {@link #admitAuthorized} while holding the
 * owner lifecycle lock and QueueCommandAuthority monitor. This object reserves
 * one ingress slot and one completion slot before publishing a Delivery. No
 * guest callback is accepted or invoked by this class.</p>
 *
 * <p>This is a runtime reference primitive, not a guest-language API and not a
 * durable broker. Payloads must already have crossed the runtime's message
 * freezing boundary before admission.</p>
 */
public final class QueueAdmissionTransaction<T> {
    public enum AdmissionStatus {
        ADMITTED, UNAUTHORIZED, FULL, COMPLETION_FULL, CLOSED, STALE_GENERATION, INVALID
    }

    public enum CompletionStatus {
        CONSUMED, REJECTED, FAILED, CANCELLED
    }

    public record Completion(
            long admissionId,
            long generation,
            ActorRuntime.ActorId sender,
            long mailboxSequence,
            CompletionStatus status) {
        public Completion {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(status, "status");
        }
    }

    public static final class Delivery<T> {
        private final QueueAdmissionTransaction<T> owner;
        private final long admissionId;
        private final long generation;
        private final ActorRuntime.ActorId sender;
        private final long mailboxSequence;
        private final T payload;
        private boolean completed;

        private Delivery(
                QueueAdmissionTransaction<T> owner,
                long admissionId,
                long generation,
                ActorRuntime.ActorId sender,
                long mailboxSequence,
                T payload) {
            this.owner = owner;
            this.admissionId = admissionId;
            this.generation = generation;
            this.sender = sender;
            this.mailboxSequence = mailboxSequence;
            this.payload = payload;
        }

        public long admissionId() { return admissionId; }
        public long generation() { return generation; }
        public ActorRuntime.ActorId sender() { return sender; }
        public long mailboxSequence() { return mailboxSequence; }
        public T payload() { return payload; }
    }

    public record AdmissionResult<T>(
            AdmissionStatus status,
            Optional<Delivery<T>> delivery) {
        public AdmissionResult {
            Objects.requireNonNull(status, "status");
            delivery = Objects.requireNonNull(delivery, "delivery");
            if ((status == AdmissionStatus.ADMITTED) != delivery.isPresent()) {
                throw new IllegalArgumentException(
                        "only ADMITTED results may carry a delivery");
            }
        }
    }

    private final int ingressCapacity;
    private final int completionCapacity;
    /** Sole bounded FIFO storage for admitted but not-yet-dispatched work. */
    private final ChannelRuntime.Channel<Delivery<T>> pending;
    private final Map<Long, Delivery<T>> inFlight = new HashMap<>();
    /** Sole bounded completion storage; reservations are retained until drained. */
    private final ChannelRuntime.Channel<Completion> completions;
    /** Bound on first successful admission; one ledger belongs to one actor incarnation. */
    private ActorRuntime.ActorId queueOwner;
    private long generation = 1;
    private long nextAdmissionId = 1;
    private int outstandingIngress;
    private int reservedCompletions;
    private boolean closed;

    public QueueAdmissionTransaction(int ingressCapacity, int completionCapacity) {
        if (ingressCapacity < 1) {
            throw new IllegalArgumentException("ingress capacity must be positive");
        }
        if (completionCapacity < 1) {
            throw new IllegalArgumentException("completion capacity must be positive");
        }
        this.ingressCapacity = ingressCapacity;
        this.completionCapacity = completionCapacity;
        this.pending = new ChannelRuntime.Channel<>(ingressCapacity);
        this.completions = new ChannelRuntime.Channel<>(completionCapacity);
    }

    public synchronized long generation() { return generation; }
    public int ingressCapacity() { return ingressCapacity; }
    public int completionCapacity() { return completionCapacity; }

    public synchronized int outstandingIngress() { return outstandingIngress; }
    public synchronized int reservedCompletions() { return reservedCompletions; }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int inFlightCount() { return inFlight.size(); }
    public synchronized int completionCount() { return completions.size(); }
    public synchronized boolean isClosed() { return closed; }

    /**
     * Called only by ActorRuntime after sender authorization has succeeded
     * while the matching queue authority and actor lifecycle locks are held.
     * Lock order is lifecycleLock -> QueueCommandAuthority -> this monitor.
     */
    synchronized AdmissionResult<T> admitAuthorized(
            ActorRuntime.ActorId queueActor,
            ActorRuntime.ActorId sender,
            long mailboxSequence,
            long expectedGeneration,
            T payload) {
        Objects.requireNonNull(queueActor, "queueActor");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(payload, "payload");
        if (closed) return rejected(AdmissionStatus.CLOSED);
        if (expectedGeneration != generation) {
            return rejected(AdmissionStatus.STALE_GENERATION);
        }
        if (outstandingIngress >= ingressCapacity) {
            return rejected(AdmissionStatus.FULL);
        }
        if (reservedCompletions >= completionCapacity) {
            return rejected(AdmissionStatus.COMPLETION_FULL);
        }
        if (queueOwner != null && !queueOwner.equals(queueActor)) {
            return rejected(AdmissionStatus.INVALID);
        }
        long id = nextAdmissionId;
        if (id <= 0 || id == Long.MAX_VALUE) {
            closed = true;
            return rejected(AdmissionStatus.CLOSED);
        }
        Delivery<T> delivery = new Delivery<>(
                this, id, generation, sender, mailboxSequence, payload);
        // Charge both independent budgets before attempting channel publication.
        // The transaction monitor prevents a poller from observing a partially
        // published reservation. Roll both counters back if the bounded channel
        // rejects the immediate write.
        outstandingIngress++;
        reservedCompletions++;
        if (!pending.tryWrite(delivery)) {
            outstandingIngress--;
            reservedCompletions--;
            return rejected(AdmissionStatus.FULL);
        }
        // Commit identity state only after the bounded channel accepts the
        // delivery, so a rejected write leaves no queue binding or ID gap.
        nextAdmissionId = id + 1;
        if (queueOwner == null) queueOwner = queueActor;
        return new AdmissionResult<>(AdmissionStatus.ADMITTED, Optional.of(delivery));
    }

    private AdmissionResult<T> rejected(AdmissionStatus status) {
        return new AdmissionResult<>(status, Optional.empty());
    }

    /** FIFO transfer from pending ingress to the in-flight set. */
    public synchronized Optional<Delivery<T>> pollDelivery() {
        Delivery<T> delivery = pending.tryRead().orElse(null);
        if (delivery == null) return Optional.empty();
        inFlight.put(delivery.admissionId, delivery);
        return Optional.of(delivery);
    }

    /**
     * Publish the one completion reserved by admission. Duplicate, foreign,
     * stale and not-yet-dispatched delivery handles are rejected.
     */
    public synchronized void complete(
            Delivery<T> delivery,
            CompletionStatus status) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(status, "status");
        if (delivery.owner != this || delivery.completed
                || inFlight.get(delivery.admissionId) != delivery) {
            throw new IllegalStateException(
                    "delivery is foreign, pending, or already completed");
        }
        if (delivery.generation != generation && !closed) {
            throw new IllegalStateException("delivery generation is no longer current");
        }
        Completion completion = new Completion(
                delivery.admissionId,
                delivery.generation,
                delivery.sender,
                delivery.mailboxSequence,
                status);
        // This write must succeed: admission reserved this exact channel slot.
        // Never drop an accepted delivery's completion silently.
        if (!completions.tryWrite(completion)) {
            throw new IllegalStateException(
                    "reserved queue completion capacity was unexpectedly unavailable");
        }
        inFlight.remove(delivery.admissionId);
        delivery.completed = true;
        outstandingIngress--;
        // reservedCompletions is deliberately retained until the consumer
        // drains this completion, so publication cannot exceed reserved space.
    }

    /** Draining a completion releases the exact capacity reserved at admission. */
    public synchronized Optional<Completion> pollCompletion() {
        Completion completion = completions.tryRead().orElse(null);
        if (completion != null) reservedCompletions--;
        return Optional.ofNullable(completion);
    }

    /** Stops future admission but preserves already-admitted FIFO work. */
    public synchronized void close() {
        closed = true;
    }

    public synchronized boolean isDrained() {
        boolean drained = closed && pending.isEmpty() && inFlight.isEmpty()
                && completions.isEmpty() && outstandingIngress == 0
                && reservedCompletions == 0;
        if (drained) {
            pending.close();
            completions.close();
        }
        return drained;
    }
}
