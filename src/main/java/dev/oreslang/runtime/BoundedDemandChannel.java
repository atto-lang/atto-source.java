package dev.oreslang.runtime;

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

    /** Prevent untrusted construction from allocating unbounded descriptor arrays. */
    public static final int MAX_QUEUED_SLOTS = 65_536;
    public static final int MAX_IN_FLIGHT = 4_096;

    private final Object gate = new Object();
    private final ChannelRuntime.Channel<T> channel;
    private final ToLongFunction<? super T> payloadBytes;
    private final long maxBytes;
    private final long maxDemand;
    private final int maxInFlight;
    /** Preallocated, bounded ring of per-message retained byte extents. */
    private final long[] queuedSizes;
    private int queuedHead;
    private int queuedTail;
    private int queuedCount;
    /** Preallocated, bounded in-flight descriptor slots; ticket 0 means free. */
    private final long[] loanTickets;
    private final long[] loanBytes;
    private int loansInUse;
    private boolean subscribed;
    private boolean complete;
    private boolean cancelled;
    private long credit;
    private long retainedBytes;
    private long nextTicket = 1;

    public BoundedDemandChannel(int capacity, int maxInFlight,
                                long maxBytes, long maxDemand,
                                ToLongFunction<? super T> payloadBytes) {
        if (capacity <= 0 || capacity > MAX_QUEUED_SLOTS ||
                maxInFlight <= 0 || maxInFlight > MAX_IN_FLIGHT ||
                maxBytes <= 0 || maxDemand <= 0) {
            throw new IllegalArgumentException("demand-port budgets must be positive and within fixed descriptor ceilings");
        }
        this.channel = new ChannelRuntime.Channel<>(capacity);
        this.queuedSizes = new long[capacity];
        this.loanTickets = new long[maxInFlight];
        this.loanBytes = new long[maxInFlight];
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
                if (loansInUse == maxInFlight) {
                    return new Poll<>(PollStatus.NOT_READY, Optional.empty());
                }
                if (queuedCount == 0) {
                    return new Poll<>(complete ? PollStatus.COMPLETE : PollStatus.NOT_READY,
                                      Optional.empty());
                }
                if (nextTicket == Long.MAX_VALUE) {
                    throw new IllegalStateException("delivery ticket space exhausted");
                }
                int freeSlot = -1;
                for (int i = 0; i < loanTickets.length; i++) {
                    if (loanTickets[i] == 0) { freeSlot = i; break; }
                }
                if (freeSlot < 0) throw new IllegalStateException("loan accounting diverged");
                T value = channel.tryRead().orElseThrow(
                        () -> new IllegalStateException("channel FIFO/accounting diverged"));
                long bytes = queuedSizes[queuedHead];
                queuedSizes[queuedHead] = 0;
                queuedHead = advance(queuedHead);
                queuedCount--;
                long ticket = nextTicket++;
                loanTickets[freeSlot] = ticket;
                loanBytes[freeSlot] = bytes;
                loansInUse++;
                return new Poll<>(PollStatus.ITEM, Optional.of(new Delivery<>(value, ticket)));
            }
        }

        /** ACK retires a physical consumer loan; duplicate/stale ACKs do nothing. */
        public boolean ack(long ticket) {
            synchronized (gate) {
                for (int i = 0; i < loanTickets.length; i++) {
                    if (ticket != 0 && loanTickets[i] == ticket) {
                        loanTickets[i] = 0;
                        retainedBytes -= loanBytes[i];
                        loanBytes[i] = 0;
                        loansInUse--;
                        return true;
                    }
                }
                return false;
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
                queuedSizes[queuedTail] = bytes;
                queuedTail = advance(queuedTail);
                queuedCount++;
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
            if (complete || cancelled) return false;
            cancelled = true;
            complete = true;
            credit = 0;
            channel.close();
            while (queuedCount != 0) {
                channel.tryRead().orElseThrow(
                        () -> new IllegalStateException("channel FIFO/accounting diverged"));
                retainedBytes -= queuedSizes[queuedHead];
                queuedSizes[queuedHead] = 0;
                queuedHead = advance(queuedHead);
                queuedCount--;
            }
            return true;
        }
    }

    private int advance(int index) {
        return index == queuedSizes.length - 1 ? 0 : index + 1;
    }

    public long retainedBytes() { synchronized (gate) { return retainedBytes; } }
    public int queuedCount() { synchronized (gate) { return queuedCount; } }
    public int inFlightCount() { synchronized (gate) { return loansInUse; } }
    public long outstandingDemand() { synchronized (gate) { return credit; } }
}
