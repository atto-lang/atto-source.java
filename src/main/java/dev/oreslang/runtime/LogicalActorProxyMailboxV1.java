package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Bounded immutable logical-mailbox core for a route-stable actor proxy.
 *
 * <p>Credits cover both queued and dispatched-but-not-completed messages.
 * Dispatch does not refund capacity. Only logical completion refunds the
 * message/byte credit, matching the native bounded-port accounting contract.</p>
 *
 * <p>Dispatch, completion and upgrade-freeze operations are intentionally
 * package-private trusted-runtime operations, not guest ActorRef methods.
 * Java package visibility is only defense in depth; production callers still
 * need compiler/runtime capability checks and authenticated physical receipts.</p>
 *
 * <p>An upgrade freeze pauses dispatch, not admission. New immutable messages
 * continue to enter the bounded queue. The freeze ticket records the highest
 * message sequence already dispatched to the old route. Commit is legal only
 * after all outstanding messages through that watermark complete.</p>
 */
public final class LogicalActorProxyMailboxV1 implements AutoCloseable {

    public record Envelope(long messageSequence, byte[] payload) {
        public Envelope {
            if (messageSequence <= 0) {
                throw new IllegalArgumentException(
                        "messageSequence must be positive");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override public byte[] payload() {
            return payload.clone();
        }

        int retainedBytes() {
            return payload.length;
        }
    }

    public record Snapshot(
            long routeEpoch,
            long nextMessageSequence,
            long highestDispatchedSequence,
            long completionWatermark,
            int retainedMessages,
            long retainedBytes,
            int queuedMessages,
            int outstandingMessages,
            int sparseCompletedMessages,
            boolean dispatchFrozen,
            boolean closed) { }

    public final class FreezeTicket implements AutoCloseable {
        private final long routeEpoch;
        private final long dispatchWatermark;
        private boolean consumed;

        private FreezeTicket(
                long routeEpoch,
                long dispatchWatermark) {
            this.routeEpoch = routeEpoch;
            this.dispatchWatermark = dispatchWatermark;
        }

        public long routeEpoch() { return routeEpoch; }
        public long dispatchWatermark() { return dispatchWatermark; }

        public boolean drained() {
            synchronized (lock) {
                requireTicketLocked(this);
                return drainedThroughLocked(dispatchWatermark);
            }
        }

        public void commit() {
            synchronized (lock) {
                requireTicketLocked(this);
                if (!drainedThroughLocked(dispatchWatermark)) {
                    throw new IllegalStateException(
                            "cannot commit logical route while old-route "
                                    + "messages remain outstanding through watermark "
                                    + dispatchWatermark);
                }
                if (routeEpoch == Long.MAX_VALUE) {
                    throw new IllegalStateException(
                            "logical route epoch exhausted");
                }
                LogicalActorProxyMailboxV1.this.routeEpoch =
                        Math.addExact(routeEpoch, 1L);
                dispatchFrozen = false;
                activeFreeze = null;
                consumed = true;
                checkStructuralInvariantsLocked();
                lock.notifyAll();
            }
        }

        public void rollback() {
            synchronized (lock) {
                requireTicketLocked(this);
                dispatchFrozen = false;
                activeFreeze = null;
                consumed = true;
                checkStructuralInvariantsLocked();
                lock.notifyAll();
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (consumed || closed) return;
                // Closing a handle is not proof that physical publication
                // failed. An implicit rollback here could resume dispatch to
                // the retired route after an ambiguous upgrade. Require an
                // explicit, supervisor-authorized rollback() or commit().
                if (activeFreeze == this) {
                    throw new IllegalStateException(
                            "active upgrade freeze requires explicit commit or rollback");
                }
                throw new IllegalStateException("stale or foreign upgrade freeze ticket");
            }
        }
    }

    private final Object lock = new Object();
    private final int maxMessages;
    private final int maxMessageBytes;
    private final long maxRetainedBytes;

    // Atomic receive-turn batch admission swaps a fully prepared private
    // deque only after every payload and quota check has passed.
    private ArrayDeque<Envelope> queued;
    private final Map<Long, Envelope> outstanding = new HashMap<>();
    private final TreeSet<Long> completedBeyondWatermark = new TreeSet<>();

    private long routeEpoch = 1L;
    private long nextMessageSequence = 1L;
    private long highestDispatchedSequence;
    private long completionWatermark;
    private int retainedMessages;
    private long retainedBytes;
    private boolean dispatchFrozen;
    private boolean closed;
    private FreezeTicket activeFreeze;

    public LogicalActorProxyMailboxV1(
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes) {
        this(maxMessages, maxMessageBytes, maxRetainedBytes, new ArrayDeque<>());
    }

    /**
     * Package-only failure-injection seam. Production always uses a private
     * ArrayDeque; the test may fail its insertion before mutation to verify
     * that sequence and capacity accounting remain exception-atomic.
     */
    LogicalActorProxyMailboxV1(
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes,
            ArrayDeque<Envelope> admissionQueue) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive");
        }
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must be positive");
        }
        if (maxRetainedBytes < maxMessageBytes) {
            throw new IllegalArgumentException(
                    "maxRetainedBytes must be at least maxMessageBytes");
        }
        // This seam is host/test-only, but it must never import untracked
        // envelopes that bypass the bounded admission and sequence counters.
        ArrayDeque<Envelope> initialQueue =
                Objects.requireNonNull(admissionQueue, "admissionQueue");
        if (!initialQueue.isEmpty()) {
            throw new IllegalArgumentException(
                    "injected logical mailbox queue must start empty");
        }
        this.maxMessages = maxMessages;
        this.maxMessageBytes = maxMessageBytes;
        this.maxRetainedBytes = maxRetainedBytes;
        this.queued = initialQueue;
    }

    /**
     * Snapshot immutable transport bytes into the logical mailbox.
     * Success means proxy admission only, never remote execution.
     */
    public Envelope admit(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        synchronized (lock) {
            requireOpenLocked();
            if (payload.length > maxMessageBytes) {
                throw new IllegalArgumentException(
                        "message exceeds logical proxy per-message byte bound");
            }
            if (retainedMessages == maxMessages) {
                throw new IllegalStateException(
                        "logical proxy mailbox message capacity exhausted");
            }
            long nextBytes = Math.addExact(retainedBytes, payload.length);
            if (nextBytes > maxRetainedBytes) {
                throw new IllegalStateException(
                        "logical proxy mailbox byte capacity exhausted");
            }
            if (nextMessageSequence == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "logical proxy message sequence exhausted");
            }

            Envelope envelope =
                    new Envelope(nextMessageSequence, payload);
            // ArrayDeque growth may allocate. Do not consume a sequence
            // until insertion succeeds: a failed admission must leave the
            // next successful envelope consecutive with the last admitted.
            queued.addLast(envelope);
            nextMessageSequence =
                    Math.addExact(nextMessageSequence, 1L);
            retainedMessages++;
            retainedBytes = nextBytes;
            checkStructuralInvariantsLocked();
            lock.notifyAll();
            return envelope;
        }
    }

    /**
     * Admit one actor receive turn's outgoing DATA as an indivisible batch
     * into this ONE logical mailbox. A successful receipt is mailbox admission
     * only, never consumer processing or global exactly-once delivery.
     *
     * This is NOT multi-edge forward_all: cross-mailbox fan-out retains its
     * per-edge, potentially partial-success result contract.
     */
    public List<Envelope> admitTurnAtomic(List<byte[]> payloads) {
        Objects.requireNonNull(payloads, "payloads");
        synchronized (lock) {
            requireOpenLocked();
            int count = payloads.size();
            if (count == 0) return List.of();
            if (count > maxMessages - retainedMessages)
                throw new IllegalStateException("logical proxy mailbox message capacity exhausted");
            if (count > Long.MAX_VALUE - nextMessageSequence)
                throw new IllegalStateException("logical proxy message sequence exhausted");

            long total = 0;
            // Validate the complete batch before allocating a copy or
            // changing any sequence or message/byte accounting.
            for (byte[] payload : payloads) {
                Objects.requireNonNull(payload, "batch DATA");
                if (payload.length > maxMessageBytes)
                    throw new IllegalArgumentException("message exceeds logical proxy per-message byte bound");
                if (payload.length > maxRetainedBytes - retainedBytes - total)
                    throw new IllegalStateException("logical proxy mailbox byte capacity exhausted");
                total += payload.length;
            }

            ArrayList<Envelope> fresh = new ArrayList<>(count);
            ArrayDeque<Envelope> prepared;
            List<Envelope> receipt;
            try {
                // Build queue and immutable receipt entirely off to the side.
                // No publication counter or live queue changes until every
                // allocation and borrowed-buffer snapshot has succeeded.
                prepared = new ArrayDeque<>(queued);
                for (byte[] payload : payloads) {
                    Envelope envelope = new Envelope(nextMessageSequence + fresh.size(), payload);
                    fresh.add(envelope);
                    prepared.addLast(envelope);
                }
                receipt = List.copyOf(fresh);
            } catch (RuntimeException | Error failed) {
                // Copies from failed preparation have never been admitted.
                for (Envelope envelope : fresh)
                    java.util.Arrays.fill(envelope.payload, (byte) 0);
                throw failed;
            }

            // No fallible insertion/receipt copy beyond this point. All
            // counters, queued bytes and publication become visible under
            // the same exclusive mailbox lock.
            queued = prepared;
            nextMessageSequence += count;
            retainedMessages += count;
            retainedBytes += total;
            checkStructuralInvariantsLocked();
            lock.notifyAll();
            return receipt;
        }
    }

    /**
     * Dispatch the next FIFO envelope to the currently authoritative route.
     * Returns null while frozen or when no message is queued.
     */
    Envelope pollDispatch() {
        synchronized (lock) {
            requireOpenLocked();
            if (dispatchFrozen) return null;
            // Bound sparse out-of-order completion metadata. Completed messages
            // refund transport credits, but they must not permit the dispatch
            // sequence to run arbitrarily far ahead of one stuck low sequence.
            // This keeps completedBeyondWatermark bounded by maxMessages.
            if (highestDispatchedSequence - completionWatermark >= maxMessages) {
                return null;
            }
            Envelope envelope = queued.peekFirst();
            if (envelope == null) return null;

            long expectedSequence =
                    Math.addExact(highestDispatchedSequence, 1L);
            if (envelope.messageSequence() != expectedSequence) {
                throw new IllegalStateException(
                        "logical proxy FIFO dispatch sequence corrupted");
            }
            if (outstanding.containsKey(envelope.messageSequence())) {
                throw new IllegalStateException(
                        "duplicate outstanding logical message sequence");
            }

            // Reserve the outstanding slot before removing the queued head.
            // HashMap growth may throw OOME; a failed reservation must not
            // silently lose the envelope or leak its retained credits.
            outstanding.put(envelope.messageSequence(), envelope);
            queued.removeFirst();
            highestDispatchedSequence = envelope.messageSequence();
            checkStructuralInvariantsLocked();
            return envelope;
        }
    }

    /**
     * Trusted transport rollback ONLY after proving physical send did not
     * accept the message. Polling dispatch reserves sequence ownership before
     * physical admission; without this operation an admission rejection would
     * strand retained byte/message credits and prevent route quiescence.
     *
     * This is deliberately package-private; never expose it to guest actors
     * or use it after an ambiguous send acknowledgement. An uncertain send
     * must instead stay outstanding/quarantined until the supervisor resolves
     * its identity, or a retry could duplicate an already accepted message.
     *
     * Only the highest dispatched, still outstanding sequence can be restored,
     * before another dispatch and while no upgrade freeze ticket is active.
     */
    void requeueProvenUnsent(long messageSequence) {
        synchronized (lock) {
            requireOpenLocked();
            if (dispatchFrozen || activeFreeze != null) {
                throw new IllegalStateException(
                        "cannot roll back physical dispatch while upgrade is frozen");
            }
            if (messageSequence != highestDispatchedSequence
                    || messageSequence <= completionWatermark
                    || !outstanding.containsKey(messageSequence)) {
                throw new IllegalStateException(
                        "physical dispatch rollback requires the latest outstanding sequence");
            }
            Envelope envelope = outstanding.get(messageSequence);
            // ArrayDeque may grow here. Enqueue before mutating outstanding
            // state so allocation failure leaves the delivery authoritative
            // and its credits intact, never half-requeued.
            queued.addFirst(envelope);
            outstanding.remove(messageSequence);
            highestDispatchedSequence--;
            // This is not logical completion: retain all message/byte credits
            // and preserve the original admission sequence for the next send.
            checkStructuralInvariantsLocked();
            lock.notifyAll();
        }
    }

    /**
     * Logical mail completion. This is the point at which proxy capacity is
     * refunded. Completion order may differ from admission/dispatch order.
     */
    void complete(long messageSequence) {
        synchronized (lock) {
            requireOpenLocked();
            Envelope envelope = outstanding.get(messageSequence);
            if (envelope == null) {
                if (messageSequence <= completionWatermark
                        || completedBeyondWatermark.contains(messageSequence)) {
                    throw new IllegalStateException(
                            "duplicate logical message completion");
                }
                throw new IllegalStateException(
                        "completion for non-dispatched logical message "
                                + messageSequence);
            }

            long nextWatermark =
                    completionWatermark == Long.MAX_VALUE
                            ? Long.MAX_VALUE
                            : completionWatermark + 1L;
            boolean advancesWatermark = messageSequence == nextWatermark;
            boolean sparseCompletion = messageSequence > completionWatermark
                    && !advancesWatermark;
            if (!advancesWatermark && !sparseCompletion) {
                throw new IllegalStateException(
                        "completion watermark moved backwards");
            }
            if (sparseCompletion) {
                if (completedBeyondWatermark.size() >= maxMessages) {
                    throw new IllegalStateException(
                            "logical completion gap metadata exceeded bounded dispatch window");
                }
                if (completedBeyondWatermark.contains(messageSequence)) {
                    throw new IllegalStateException(
                            "duplicate sparse logical completion");
                }
            }

            int nextRetainedMessages = retainedMessages - 1;
            long nextRetainedBytes = retainedBytes - envelope.retainedBytes();
            if (nextRetainedMessages < 0 || nextRetainedBytes < 0) {
                throw new IllegalStateException(
                        "logical proxy capacity accounting underflow");
            }

            // TreeSet insertion may allocate. Reserve sparse-completion
            // metadata before refunding credits/removing the delivery: if an
            // allocation fails, it must remain outstanding and retryable.
            if (sparseCompletion && !completedBeyondWatermark.add(messageSequence)) {
                throw new IllegalStateException(
                        "duplicate sparse logical completion");
            }

            // No allocation-bearing insertion remains on the commit path.
            outstanding.remove(messageSequence);
            retainedMessages = nextRetainedMessages;
            retainedBytes = nextRetainedBytes;

            if (advancesWatermark) {
                completionWatermark = messageSequence;
                while (completionWatermark != Long.MAX_VALUE
                        && completedBeyondWatermark.remove(
                                completionWatermark + 1L)) {
                    completionWatermark++;
                }
            }
            checkStructuralInvariantsLocked();
            lock.notifyAll();
        }
    }

    /**
     * Freeze dispatch at an exact old-route watermark. Admission remains open.
     */
    FreezeTicket freezeDispatchForUpgrade() {
        synchronized (lock) {
            requireOpenLocked();
            if (dispatchFrozen || activeFreeze != null) {
                throw new IllegalStateException(
                        "logical proxy dispatch is already frozen");
            }
            // Construct first. Allocation failure must never strand ingress
            // in a frozen state with no valid rollback ticket.
            FreezeTicket ticket =
                    new FreezeTicket(routeEpoch, highestDispatchedSequence);
            activeFreeze = ticket;
            dispatchFrozen = true;
            checkStructuralInvariantsLocked();
            return ticket;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            checkStructuralInvariantsLocked();
            return new Snapshot(
                    routeEpoch,
                    nextMessageSequence,
                    highestDispatchedSequence,
                    completionWatermark,
                    retainedMessages,
                    retainedBytes,
                    queued.size(),
                    outstanding.size(),
                    completedBeyondWatermark.size(),
                    dispatchFrozen,
                    closed);
        }
    }

    private boolean drainedThroughLocked(long watermark) {
        // completionWatermark is the greatest *contiguous* completed
        // dispatch sequence. Sparse later receipts cannot advance it over
        // a missing old-route message, so this is equivalent to scanning
        // every outstanding key <= watermark, but O(1) during a frozen
        // upgrade even with a large bounded logical mailbox.
        //
        // Do not replace this with outstanding.isEmpty(): admissions after
        // freeze are queued for the successor, and later completed messages
        // must not certify an unresolved old dispatch.
        return completionWatermark >= watermark;
    }

    private void checkStructuralInvariantsLocked() {
        if (retainedMessages < 0 || retainedMessages > maxMessages
                || retainedMessages != queued.size() + outstanding.size()) {
            throw new IllegalStateException(
                    "logical proxy message accounting invariant violated");
        }
        if (retainedBytes < 0 || retainedBytes > maxRetainedBytes) {
            throw new IllegalStateException(
                    "logical proxy byte accounting invariant violated");
        }
        if (completionWatermark < 0
                || completionWatermark > highestDispatchedSequence
                || highestDispatchedSequence >= nextMessageSequence) {
            throw new IllegalStateException(
                    "logical proxy sequence invariant violated");
        }
        if (highestDispatchedSequence - completionWatermark > maxMessages
                || completedBeyondWatermark.size() > maxMessages) {
            throw new IllegalStateException(
                    "logical proxy bounded completion-window invariant violated");
        }
        if (!closed && dispatchFrozen != (activeFreeze != null)) {
            throw new IllegalStateException(
                    "logical proxy freeze-state invariant violated");
        }
        if (activeFreeze != null && activeFreeze.routeEpoch != routeEpoch) {
            throw new IllegalStateException(
                    "logical proxy freeze route invariant violated");
        }
    }

    private void requireTicketLocked(FreezeTicket ticket) {
        if (closed) {
            throw new IllegalStateException(
                    "logical proxy mailbox is closed");
        }
        if (ticket == null
                || ticket.consumed
                || activeFreeze != ticket
                || !dispatchFrozen
                || ticket.routeEpoch != routeEpoch) {
            throw new IllegalStateException(
                    "stale or foreign logical proxy freeze ticket");
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw new IllegalStateException(
                    "logical proxy mailbox is closed");
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            dispatchFrozen = true;
            if (activeFreeze != null) {
                activeFreeze.consumed = true;
                activeFreeze = null;
            }
            queued.clear();
            outstanding.clear();
            completedBeyondWatermark.clear();
            retainedMessages = 0;
            retainedBytes = 0;
            checkStructuralInvariantsLocked();
            lock.notifyAll();
        }
    }
}
