package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime reference for the bounded-queue v1 contract.
 *
 * <p>This class deliberately reuses {@link ChannelRuntime.Channel} as the only
 * buffer, waiter registry and admission coordinator. It does not maintain a
 * second pending-writer list. Queue tickets are one-shot cancellation
 * capabilities whose pending authority is the exact channel write Future.
 * Therefore cancellation and write commit linearize through the existing
 * channel select coordinator.</p>
 */
public final class BoundedQueueRuntime<T> implements AutoCloseable {
    private static final AtomicLong NEXT_QUEUE_ID = new AtomicLong();

    public enum PushStatus {
        ADMITTED,
        FULL,
        CANCELLED,
        CLOSED,
        STALE_TICKET,
        TICKET_USED,
        RESOURCE_LIMIT
    }

    public enum PopStatus {
        VALUE,
        EMPTY,
        CLOSED_DRAINED
    }

    public record PopResult<T>(PopStatus status, T value) {
        public PopResult {
            Objects.requireNonNull(status, "status");
            if (status == PopStatus.VALUE) {
                Objects.requireNonNull(value, "VALUE pop requires a value");
            } else if (value != null) {
                throw new IllegalArgumentException(
                        status + " pop cannot carry a value");
            }
        }

        public static <T> PopResult<T> value(T value) {
            return new PopResult<>(PopStatus.VALUE, value);
        }

        public static <T> PopResult<T> empty() {
            return new PopResult<>(PopStatus.EMPTY, null);
        }

        public static <T> PopResult<T> closedDrained() {
            return new PopResult<>(PopStatus.CLOSED_DRAINED, null);
        }
    }

    private enum TicketState {
        ISSUED,
        CLAIMED,
        CANCELLED,
        SETTLED
    }

    /**
     * One push-attempt identity. Tickets are local runtime capabilities; they
     * are not actor-message data.
     */
    public static final class Ticket {
        private final long queueId;
        private final long generation;
        private final long sequence;

        private TicketState state = TicketState.ISSUED;
        private OresFuture<Void> pendingWrite;

        private Ticket(long queueId, long generation, long sequence) {
            this.queueId = queueId;
            this.generation = generation;
            this.sequence = sequence;
        }

        public long generation() {
            return generation;
        }

        public long sequence() {
            return sequence;
        }

        public synchronized boolean isCancelled() {
            return state == TicketState.CANCELLED;
        }

        public synchronized boolean isSettled() {
            return state == TicketState.CANCELLED
                    || state == TicketState.SETTLED;
        }

        /**
         * Request cancellation of this push attempt.
         *
         * <p>For an already-claimed pending write, the channel Future's
         * cancellation admission is authoritative. ChannelRuntime refuses that
         * cancellation once the write selection has committed, even if result
         * publication has not run yet. Thus true means cancellation really won
         * before admission; it can never revoke a committed payload.</p>
         */
        public synchronized boolean cancel() {
            return switch (state) {
                case ISSUED -> {
                    state = TicketState.CANCELLED;
                    yield true;
                }
                case CLAIMED -> {
                    if (pendingWrite == null) {
                        throw new IllegalStateException(
                                "claimed queue ticket has no pending write");
                    }
                    boolean won = pendingWrite.cancel(false);
                    if (won) {
                        state = TicketState.CANCELLED;
                    } else if (pendingWrite.isDone()) {
                        state = TicketState.SETTLED;
                    }
                    yield won;
                }
                case CANCELLED, SETTLED -> false;
            };
        }

        private synchronized PushStatus claimOrTerminal() {
            return switch (state) {
                case ISSUED -> {
                    state = TicketState.CLAIMED;
                    yield null;
                }
                case CANCELLED -> PushStatus.CANCELLED;
                case CLAIMED, SETTLED -> PushStatus.TICKET_USED;
            };
        }

        /**
         * Called while this ticket monitor is already held by pushAsync setup.
         */
        private void bindPendingWriteLocked(OresFuture<Void> pendingWrite) {
            if (!Thread.holdsLock(this)) {
                throw new IllegalStateException(
                        "pending write binding requires ticket monitor");
            }
            if (state != TicketState.CLAIMED || this.pendingWrite != null) {
                throw new IllegalStateException(
                        "queue ticket pending write may be bound exactly once");
            }
            this.pendingWrite = Objects.requireNonNull(pendingWrite, "pendingWrite");
        }

        private synchronized void settle() {
            if (state == TicketState.CLAIMED) {
                state = TicketState.SETTLED;
            }
        }

        private synchronized void settleCancelled() {
            if (state == TicketState.CLAIMED) {
                state = TicketState.CANCELLED;
            }
        }

        private synchronized void settleWithoutClaim() {
            if (state == TicketState.ISSUED) {
                state = TicketState.SETTLED;
            }
        }
    }

    private final Object stateLock = new Object();
    private final long queueId;
    private final ChannelRuntime.Channel<T> items;
    private long generation = 1;
    private long nextTicket = 1;

    public BoundedQueueRuntime(int capacity) {
        this(capacity, ChannelRuntime.DEFAULT_MAX_PENDING_REGISTRATIONS);
    }

    /**
     * Explicit waiter budget constructor used by constrained targets and
     * deterministic conformance tests.
     */
    public BoundedQueueRuntime(int capacity, int maxPendingRegistrations) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "bounded queue capacity must be positive");
        }
        if (maxPendingRegistrations < 1) {
            throw new IllegalArgumentException(
                    "bounded queue pending registration budget must be positive");
        }
        long allocated = NEXT_QUEUE_ID.incrementAndGet();
        if (allocated <= 0) {
            throw new IllegalStateException(
                    "bounded queue identity space exhausted");
        }
        this.queueId = allocated;
        this.items =
                new ChannelRuntime.Channel<>(capacity, maxPendingRegistrations);
    }

    public int capacity() {
        return items.capacity();
    }

    public int size() {
        return items.size();
    }

    public int pendingRegistrations() {
        return items.pendingRegistrations();
    }

    public boolean isClosed() {
        return items.isClosed();
    }

    public long generation() {
        synchronized (stateLock) {
            return generation;
        }
    }

    public Ticket newTicket() {
        synchronized (stateLock) {
            if (items.isClosed()) {
                throw new ChannelRuntime.ChannelClosedException(
                        "bounded queue is closed");
            }
            long sequence = nextTicket++;
            if (sequence <= 0) {
                throw new IllegalStateException(
                        "bounded queue ticket identity space exhausted");
            }
            return new Ticket(queueId, generation, sequence);
        }
    }

    /**
     * Immediate push. FULL never retains a writer registration.
     */
    public PushStatus tryPush(T value, Ticket ticket) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ticket, "ticket");

        synchronized (stateLock) {
            PushStatus preflight = preflight(ticket);
            if (preflight != null) return preflight;

            PushStatus claimed = ticket.claimOrTerminal();
            if (claimed != null) return claimed;

            boolean admitted;
            try {
                admitted = items.tryWrite(value);
            } catch (ChannelRuntime.ChannelClosedException closed) {
                ticket.settle();
                return PushStatus.CLOSED;
            }

            ticket.settle();
            return admitted ? PushStatus.ADMITTED : PushStatus.FULL;
        }
    }

    /**
     * Scheduler-friendly pending push.
     *
     * <p>The returned Future completes with an ordinary PushStatus. Cancelling
     * that returned Future delegates to the same ticket cancellation authority,
     * so there is still only one cancellation race against the underlying
     * channel write.</p>
     */
    public OresFuture<PushStatus> pushAsync(T value, Ticket ticket) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ticket, "ticket");

        final OresFuture<Void> pending;
        synchronized (stateLock) {
            PushStatus preflight = preflight(ticket);
            if (preflight != null) return OresFuture.completed(preflight);

            synchronized (ticket) {
                PushStatus claimed = ticket.claimOrTerminal();
                if (claimed != null) return OresFuture.completed(claimed);

                try {
                    pending = items.writeAsync(value);
                } catch (ChannelRuntime.ChannelClosedException closed) {
                    ticket.settle();
                    return OresFuture.completed(PushStatus.CLOSED);
                }
                ticket.bindPendingWriteLocked(pending);
            }
        }

        OresFuture<PushStatus> result =
                new OresFuture<>(ticket::cancel, () -> { });

        pending.whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                ticket.settle();
                result.completeFromRuntime(PushStatus.ADMITTED);
                return;
            }

            Throwable terminal = OresFuture.unwrap(failure);
            if (pending.isCancelled()
                    || terminal instanceof CancellationException) {
                ticket.settleCancelled();
                result.completeFromRuntime(PushStatus.CANCELLED);
            } else if (terminal instanceof ChannelRuntime.ChannelClosedException) {
                ticket.settle();
                result.completeFromRuntime(PushStatus.CLOSED);
            } else if (terminal instanceof ChannelRuntime.ChannelBackpressureException
                    || terminal instanceof ChannelRuntime.SelectAdmissionException) {
                ticket.settle();
                result.completeFromRuntime(PushStatus.RESOURCE_LIMIT);
            } else {
                ticket.settle();
                result.failFromRuntime(terminal);
            }
        });

        return result;
    }

    /**
     * Explicit ticket cancellation for callers that do not own the returned
     * Future handle.
     */
    public boolean cancel(Ticket ticket) {
        requireOwner(Objects.requireNonNull(ticket, "ticket"));
        return ticket.cancel();
    }

    /**
     * Immediate receive with an explicit open-empty versus closed-drained
     * distinction. Once closed is observed, a second probe is authoritative:
     * close prevents any later admission, while it may have raced an already
     * committed value into the buffer after the first empty probe.
     */
    public PopResult<T> tryPop() {
        Optional<T> first = items.tryRead();
        if (first.isPresent()) return PopResult.value(first.get());

        if (!items.isClosed()) return PopResult.empty();

        Optional<T> afterClose = items.tryRead();
        return afterClose
                .<PopResult<T>>map(PopResult::value)
                .orElseGet(PopResult::closedDrained);
    }

    /**
     * Scheduler-friendly drain-before-terminal receive.
     */
    public OresFuture<PopResult<T>> popAsync() {
        OresFuture<T> pending = items.readAsync();
        OresFuture<PopResult<T>> result =
                new OresFuture<>(() -> pending.cancel(false), () -> { });

        pending.whenCompleteRuntime((value, failure) -> {
            if (failure == null) {
                result.completeFromRuntime(PopResult.value(value));
                return;
            }

            Throwable terminal = OresFuture.unwrap(failure);
            if (pending.isCancelled()
                    || terminal instanceof CancellationException) {
                result.cancel(false);
            } else if (terminal instanceof ChannelRuntime.ChannelClosedException) {
                result.completeFromRuntime(PopResult.closedDrained());
            } else if (terminal instanceof ChannelRuntime.ChannelBackpressureException
                    || terminal instanceof ChannelRuntime.SelectAdmissionException) {
                result.failFromRuntime(terminal);
            } else {
                result.failFromRuntime(terminal);
            }
        });
        return result;
    }

    /**
     * Idempotent close. Generation advances before channel close is published,
     * invalidating every unclaimed ticket from the previous generation.
     *
     * <p>Channel.close() is the admission linearization point for pending
     * writers. It rejects uncommitted writes and preserves committed buffered
     * data for drain.</p>
     */
    public boolean closeQueue() {
        synchronized (stateLock) {
            if (items.isClosed()) return false;
            generation = Math.addExact(generation, 1L);
            items.close();
            return true;
        }
    }

    @Override
    public void close() {
        closeQueue();
    }

    private PushStatus preflight(Ticket ticket) {
        requireOwner(ticket);

        if (ticket.generation != generation) {
            ticket.settleWithoutClaim();
            return PushStatus.STALE_TICKET;
        }
        if (items.isClosed()) {
            ticket.settleWithoutClaim();
            return PushStatus.CLOSED;
        }
        return null;
    }

    private void requireOwner(Ticket ticket) {
        if (ticket.queueId != queueId) {
            throw new IllegalArgumentException(
                    "queue ticket belongs to a different BoundedQueue");
        }
    }
}
