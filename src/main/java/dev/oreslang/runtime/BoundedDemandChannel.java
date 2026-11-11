package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * Trusted host adapter from the existing bounded ChannelRuntime substrate to a
 * single-consumer, credit-driven pull/push boundary.
 *
 * <p>This is NOT a guest Flow intrinsic or an ownership checker. Source-level
 * successful-move / rejected-send-retains-value semantics must be enforced by
 * the compiler, and the payload sizer must measure a validated immutable
 * snapshot before source ownership changes. No host callback is scheduled here.
 * No async waiter is registered: a failed offer is immediate and retryable.</p>
 *
 * <p>Capacity zero is intentionally rejected here: this try-only adapter has
 * no registered rendezvous reader. Use ChannelRuntime's async operations for
 * genuine zero-capacity rendezvous.</p>
 */
public final class BoundedDemandChannel<T> {
    public enum Refusal { NONE, NO_SUBSCRIBER, NO_DEMAND, FULL, BYTE_BUDGET, CLOSED }
    public enum PollStatus { ITEM, NOT_READY, COMPLETE, CANCELLED }

    /** A rejected offer returns exactly the original value reference. */
    public record Offer<T>(boolean admitted, Optional<T> retained, Refusal reason) {
        public Offer {
            Objects.requireNonNull(retained, "retained");
            Objects.requireNonNull(reason, "reason");
            if (admitted != (reason == Refusal.NONE && retained.isEmpty())) {
                throw new IllegalArgumentException("invalid offer outcome");
            }
            if (!admitted && retained.isEmpty()) {
                throw new IllegalArgumentException("rejected offer must retain payload");
            }
        }
    }

    public record Delivery<T>(T value, long ticket) {
        public Delivery { Objects.requireNonNull(value, "value"); }
    }

    public record Poll<T>(PollStatus status, Optional<Delivery<T>> delivery) {
        public Poll {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(delivery, "delivery");
            if ((status == PollStatus.ITEM) != delivery.isPresent()) {
                throw new IllegalArgumentException("invalid poll outcome");
            }
        }
    }

    private final Object gate = new Object();
    private final ChannelRuntime.Channel<T> channel;
    private final ToLongFunction<? super T> payloadBytes;
    private final long maxBytes;
    private final long maxDemand;
    private final int maxInFlight;
    private final ArrayDeque<Long> queuedSizes = new ArrayDeque<>();
    private final Map<Long, Long> inFlight = new HashMap<>();
    private boolean subscribed;
    private boolean complete;
    private boolean cancelled;
    private long credit;
    private long retainedBytes;
    private long nextTicket = 1;

    public BoundedDemandChannel(int capacity, int maxInFlight,
                                long maxBytes, long maxDemand,
                                ToLongFunction<? super T> payloadBytes) {
        if (capacity <= 0 || maxInFlight <= 0 || maxBytes <= 0 || maxDemand <= 0) {
            throw new IllegalArgumentException("all demand-port budgets must be positive");
        }
        this.channel = new ChannelRuntime.Channel<>(capacity);
        this.maxInFlight = maxInFlight;
        this.maxBytes = maxBytes;
        this.maxDemand = maxDemand;
        this.payloadBytes = Objects.requireNonNull(payloadBytes, "payloadBytes");
    }

    /** A single logical subscriber is bound to this port for its entire life. */
    public Subscription subscribe() {
        synchronized (gate) {
            if (subscribed || complete || cancelled) {
                throw new IllegalStateException("reader lease already owned or port terminated");
            }
            subscribed = true;
            return new Subscription();
        }
    }

    public final class Subscription {
        private Subscription() { }

        public void request(long n) {
            if (n <= 0) throw new IllegalArgumentException("demand must be positive");
            synchronized (gate) {
                if (complete || cancelled) throw new IllegalStateException("port terminated");
                if (n > maxDemand - credit) {
                    throw new IllegalArgumentException("outstanding demand budget exhausted");
                }
                credit += n;
            }
        }

        public Poll<T> poll() {
            synchronized (gate) {
                if (cancelled) return new Poll<>(PollStatus.CANCELLED, Optional.empty());
                if (inFlight.size() == maxInFlight) {
                    return new Poll<>(PollStatus.NOT_READY, Optional.empty());
                }
                if (queuedSizes.isEmpty()) {
                    return new Poll<>(complete ? PollStatus.COMPLETE : PollStatus.NOT_READY,
                                      Optional.empty());
                }
                if (nextTicket == Long.MAX_VALUE) {
                    throw new IllegalStateException("delivery ticket space exhausted");
                }
                T value = channel.tryRead().orElseThrow(
                        () -> new IllegalStateException("channel FIFO/accounting diverged"));
                long bytes = queuedSizes.removeFirst();
                long ticket = nextTicket++;
                inFlight.put(ticket, bytes);
                return new Poll<>(PollStatus.ITEM, Optional.of(new Delivery<>(value, ticket)));
            }
        }

        /** ACK retires a physical consumer loan; duplicate/stale ACKs do nothing. */
        public boolean ack(long ticket) {
            synchronized (gate) {
                Long bytes = inFlight.remove(ticket);
                if (bytes == null) return false;
                retainedBytes -= bytes;
                return true;
            }
        }

        public boolean cancel() { return BoundedDemandChannel.this.cancel(); }
    }

    /**
     * Immediate producer admission. A refusal never consumes the value.
     * Static ownership/move semantics are a separate compiler obligation.
     */
    public Offer<T> offer(T value) {
        Objects.requireNonNull(value, "value");
        long bytes = payloadBytes.applyAsLong(value);
        if (bytes <= 0 || bytes > maxBytes) {
            throw new IllegalArgumentException("payload has invalid measured byte extent");
        }
        synchronized (gate) {
            Refusal refusal;
            if (complete || cancelled) refusal = Refusal.CLOSED;
            else if (!subscribed) refusal = Refusal.NO_SUBSCRIBER;
            else if (credit == 0) refusal = Refusal.NO_DEMAND;
            else if (bytes > maxBytes - retainedBytes) refusal = Refusal.BYTE_BUDGET;
            else if (!channel.tryWrite(value)) refusal = Refusal.FULL;
            else {
                credit--;
                retainedBytes += bytes;
                queuedSizes.addLast(bytes);
                return new Offer<>(true, Optional.empty(), Refusal.NONE);
            }
            return new Offer<>(false, Optional.of(value), refusal);
        }
    }

    /** Graceful completion preserves already committed FIFO values. */
    public boolean complete() {
        synchronized (gate) {
            if (complete || cancelled) return false;
            complete = true;
            credit = 0;
            channel.close();
            return true;
        }
    }

    /** Cancellation drops queued values once, preserving in-flight byte loans. */
    public boolean cancel() {
        synchronized (gate) {
            if (cancelled) return false;
            cancelled = true;
            complete = true;
            credit = 0;
            channel.close();
            while (!queuedSizes.isEmpty()) {
                channel.tryRead().orElseThrow(
                        () -> new IllegalStateException("channel FIFO/accounting diverged"));
                retainedBytes -= queuedSizes.removeFirst();
            }
            return true;
        }
    }

    public long retainedBytes() { synchronized (gate) { return retainedBytes; } }
    public int queuedCount() { synchronized (gate) { return queuedSizes.size(); } }
    public int inFlightCount() { synchronized (gate) { return inFlight.size(); } }
    public long outstandingDemand() { synchronized (gate) { return credit; } }
}
