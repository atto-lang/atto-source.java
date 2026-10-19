package dev.oreslang.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
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

    public record Envelope(
            long messageSequence,
            byte[] payload,
            String payloadSha256) {
        public Envelope(long messageSequence, byte[] payload) {
            this(messageSequence, payload, null);
        }

        public Envelope {
            if (messageSequence <= 0) {
                throw new IllegalArgumentException(
                        "messageSequence must be positive");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
            // Never trust a caller-supplied digest. The mailbox-owned
            // immutable bytes mint their own canonical content identity.
            payloadSha256 = sha256(payload);
        }

        @Override public byte[] payload() {
            return payload.clone();
        }

        int retainedBytes() {
            return payload.length;
        }

        boolean payloadIntegrityValid() {
            return payloadSha256.equals(sha256(payload));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable for logical actor envelope identity",
                    impossible);
        }
    }
    private static void requirePayloadIntegrity(Envelope envelope) {
        if (!envelope.payloadIntegrityValid()) {
            throw new SecurityException(
                    "logical proxy envelope payload identity mismatch");
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
                // Verify invariants BEFORE the irreversible publication.
                // No allocation or throwing validation may follow thaw: the
                // code-generation gate may already have published new code.
                checkStructuralInvariantsLocked();
                LogicalActorProxyMailboxV1.this.routeEpoch =
                        routeEpoch + 1L; // overflow checked above
                dispatchFrozen = false;
                activeFreeze = null;
                consumed = true;
                lock.notifyAll();
            }
        }

        public void rollback() {
            synchronized (lock) {
                requireTicketLocked(this);
                // If corruption is detected, retain the frozen old route.
                checkStructuralInvariantsLocked();
                dispatchFrozen = false;
                activeFreeze = null;
                consumed = true;
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

    private final ArrayDeque<Envelope> queued;
    private final Map<Long, Envelope> outstanding = new HashMap<>();
    // This ledger records *attempted* physical admission, not completed work.
    // An ambiguous send must remain reserved until trusted reconciliation.
    private final HashSet<Long> physicalSendAttempted = new HashSet<>();
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
     * Trusted transport rollback ONLY before a physical send attempt has
     * been recorded. Polling dispatch reserves sequence ownership before
     * physical admission; this operation recovers a never-submitted dispatch
     * without refunding any retained transport credit.
     *
     * This is deliberately package-private. A sequence-only caller has no
     * verifiable proof of non-acceptance after beginPhysicalSendAttempt().
     * Therefore no recorded attempt can be requeued by this V1 API, even if
     * a caller claims an external transport rejection. Uncertain sends stay
     * outstanding/quarantined; any future retry must require a separately
     * authenticated non-acceptance receipt with exact mailbox/epoch identity.
     *
     * Only the exact highest-dispatched Envelope object in the expected route
     * epoch can be restored, before another dispatch and while no upgrade
     * freeze ticket is active. A copied envelope or bare sequence is never
     * rollback authority.
     */
    void requeueProvenUnsent(
            Envelope envelope, long expectedRouteEpoch) {
        Objects.requireNonNull(envelope, "envelope");
        synchronized (lock) {
            requireOpenLocked();
            if (dispatchFrozen || activeFreeze != null) {
                throw new IllegalStateException(
                        "cannot roll back physical dispatch while upgrade is frozen");
            }
            long messageSequence = envelope.messageSequence();
            Long key = Long.valueOf(messageSequence);
            if (expectedRouteEpoch != routeEpoch
                    || messageSequence != highestDispatchedSequence
                    || messageSequence <= completionWatermark
                    || outstanding.get(key) != envelope) {
                throw new SecurityException(
                        "physical dispatch rollback requires the exact latest "
                                + "outstanding envelope in the current route epoch");
            }
            if (physicalSendAttempted.contains(key)) {
                throw new IllegalStateException(
                        "cannot requeue a physically attempted message without an "
                                + "authenticated proof of non-acceptance");
            }
            requirePayloadIntegrity(envelope);
            // ArrayDeque may grow here. Enqueue before mutating outstanding
            // state so allocation failure leaves the delivery authoritative
            // and its credits intact, never half-requeued.
            queued.addFirst(envelope);
            outstanding.remove(key);
            // Never erase an attempted-send authorization: the assertion
            // above proves this envelope was not physically submitted.
            highestDispatchedSequence--;
            // This is not logical completion: retain all message/byte credits
            // and preserve the original admission sequence for the next send.
            checkStructuralInvariantsLocked();
            lock.notifyAll();
        }
    }

    /**
     * Trusted physical-router ownership proof. A matching sequence or copied
     * payload is not sufficient: the SAME immutable Envelope object must be
     * currently outstanding in THIS mailbox and owned by THIS route epoch.
     * Call before physical acceptance and again under the route monitor
     * immediately before a successful drain refunds credits.
     */
    void requireOutstandingEnvelope(Envelope envelope, long expectedRouteEpoch) {
        Objects.requireNonNull(envelope, "envelope");
        synchronized (lock) {
            requireOpenLocked();
            if (expectedRouteEpoch != routeEpoch
                    || outstanding.get(envelope.messageSequence()) != envelope) {
                throw new SecurityException(
                        "foreign, completed or stale logical proxy envelope");
            }
            requirePayloadIntegrity(envelope);
        }
    }

    /**
     * Reserve a single trusted physical submission for an already dispatched
     * envelope. This check and ledger insertion share the mailbox monitor:
     * parallel trusted senders cannot submit one logical sequence twice.
     * Reservation happens BEFORE physical mailbox acceptance; a send timeout
     * or ambiguous failure must not clear it or automatically replay work.
     * Only supervisor-proven-unsent requeue or terminal completion removes it.
     */
    void beginPhysicalSendAttempt(Envelope envelope, long expectedRouteEpoch) {
        Objects.requireNonNull(envelope, "envelope");
        synchronized (lock) {
            requireOpenLocked();
            if (dispatchFrozen) {
                throw new IllegalStateException(
                        "physical send cannot start after an upgrade dispatch freeze");
            }
            if (expectedRouteEpoch != routeEpoch
                    || outstanding.get(envelope.messageSequence()) != envelope) {
                throw new SecurityException(
                        "foreign, stale or non-outstanding physical send attempt");
            }
            if (physicalSendAttempted.contains(envelope.messageSequence())) {
                throw new IllegalStateException(
                        "logical proxy envelope already submitted to physical transport");
            }
            // Re-verify the mailbox-minted content identity before granting
            // the one-shot physical-send authority. This normally cannot fail
            // through the public API because payload() returns a clone, but it
            // catches accidental/reflection corruption before actor admission.
            requirePayloadIntegrity(envelope);
            // HashSet growth can fail before send; never submit in that case.
            physicalSendAttempted.add(envelope.messageSequence());
        }
    }

    /**
     * Logical mail completion. This is the point at which proxy capacity is
     * refunded. Completion order may differ from admission/dispatch order.
     */
    /**
     * The real actor-router completion authority. This is not a generic
     * sequence acknowledgment: the exact immutable Envelope must still be
     * outstanding in the expected physical route epoch, and a physical send
     * reservation must precede the terminal actor receipt. Validate and
     * settle under ONE mailbox monitor to exclude forged, stale and raced
     * credit refunds.
     */
    void completeAfterPhysicalReceipt(
            Envelope envelope, long expectedRouteEpoch) {
        Objects.requireNonNull(envelope, "envelope");
        synchronized (lock) {
            requireOpenLocked();
            Long key = Long.valueOf(envelope.messageSequence());
            if (routeEpoch != expectedRouteEpoch
                    || outstanding.get(key) != envelope
                    || !physicalSendAttempted.contains(key)) {
                throw new SecurityException(
                        "missing, foreign or stale physical completion authority");
            }
            requirePayloadIntegrity(envelope);
            completeLocked(envelope.messageSequence());
        }
    }

    private void completeLocked(long messageSequence) {
            requireOpenLocked();
            // A boxed sequence key may allocate outside Java's Long cache.
            // Reserve it before the first completion/accounting mutation and
            // reuse it for every map and set operation on this message.
            Long key = Long.valueOf(messageSequence);
            Envelope envelope = outstanding.get(key);
            if (envelope == null) {
                if (messageSequence <= completionWatermark
                        || completedBeyondWatermark.contains(key)) {
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
                if (completedBeyondWatermark.contains(key)) {
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
            if (sparseCompletion && !completedBeyondWatermark.add(key)) {
                throw new IllegalStateException(
                        "duplicate sparse logical completion");
            }

            // No allocation-bearing insertion remains on the commit path.
            outstanding.remove(key);
            physicalSendAttempted.remove(key);
            retainedMessages = nextRetainedMessages;
            retainedBytes = nextRetainedBytes;

            if (advancesWatermark) {
                completionWatermark = messageSequence;
                // first()/pollFirst() reuse TreeSet's existing Long keys;
                // do not allocate a fresh boxed next-sequence key after
                // completion has already refunded its transport credits.
                while (completionWatermark != Long.MAX_VALUE
                        && !completedBeyondWatermark.isEmpty()
                        && completedBeyondWatermark.first().longValue()
                                == completionWatermark + 1L) {
                    completionWatermark = completedBeyondWatermark.pollFirst();
                }
            }
            checkStructuralInvariantsLocked();
            lock.notifyAll();
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
            checkStructuralInvariantsLocked();
            FreezeTicket ticket =
                    new FreezeTicket(routeEpoch, highestDispatchedSequence);
            activeFreeze = ticket;
            dispatchFrozen = true;
            return ticket;
        }
    }

    /**
     * Supervisor-only publication preflight. The ticket must be the exact
     * active freeze object owned by THIS mailbox, not an equal-looking
     * route epoch or another mailbox's freeze. This operation never thaws.
     */
    void requireFreezeTicketForRouteEpoch(
            FreezeTicket ticket, long expectedRouteEpoch) {
        synchronized (lock) {
            requireTicketLocked(ticket);
            checkStructuralInvariantsLocked();
            if (routeEpoch != expectedRouteEpoch
                    || ticket.routeEpoch != expectedRouteEpoch
                    || !drainedThroughLocked(ticket.dispatchWatermark)) {
                throw new IllegalStateException(
                        "foreign epoch or undrained proxy publication ticket");
            }
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
        for (Long sequence : outstanding.keySet()) {
            if (sequence <= watermark) return false;
        }
        return true;
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
        // This is a constant-time hot-path bound. The stronger subset
        // relationship follows from begin/requeue/complete/close all mutating
        // the attempt ledger under this exact mailbox lock; scanning every
        // outstanding sequence here would make admission O(window).
        if (physicalSendAttempted.size() > outstanding.size()) {
            throw new IllegalStateException(
                    "physical attempt ledger exceeds outstanding messages");
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
            physicalSendAttempted.clear();
            completedBeyondWatermark.clear();
            retainedMessages = 0;
            retainedBytes = 0;
            checkStructuralInvariantsLocked();
            lock.notifyAll();
        }
    }
}
