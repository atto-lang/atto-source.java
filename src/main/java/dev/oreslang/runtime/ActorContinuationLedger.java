package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Actor-owned receipt ledger for admitted continuation envelopes.
 *
 * <p>The scheduler owns runnable placement; this ledger independently owns the
 * identity and state of each admitted callback. It is bounded by the actor's
 * existing internal continuation-slot quota and deliberately does not schedule
 * guest work or retain a carrier. The actor's physical execution lease remains
 * authoritative for entering guest code and for retirement.</p>
 *
 * <p>All transitions linearize on this ledger's monitor. A continuation
 * completion and STOP may race, but only one may claim a queued receipt.
 * SOURCE_TASK cleanup remains admitted after ordinary callbacks are sealed;
 * it is never permission to execute new user messages.</p>
 */
final class ActorContinuationLedger {
    enum Lane { ORDINARY, SOURCE_TASK }
    private enum State { QUEUED, RUNNING, RETIRED, DISCARDED }

    record Snapshot(long generation, long lastSequence, int queued, int running) { }

    private final int capacity;
    private final IdentityHashMap<Admission, Boolean> live = new IdentityHashMap<>();
    private long generation = 1L;
    private long lastSequence;
    private boolean ordinarySealed;
    private boolean allSealed;

    ActorContinuationLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("ledger capacity must be positive");
        this.capacity = capacity;
    }

    synchronized Admission admit(Lane lane, Runnable callback) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(callback, "callback");
        if (allSealed || (lane == Lane.ORDINARY && ordinarySealed)) {
            throw new IllegalStateException("actor continuation ledger admission sealed");
        }
        if (live.size() >= capacity) {
            throw new IllegalStateException("actor continuation ledger at capacity");
        }
        long sequence = Math.addExact(lastSequence, 1L);
        Admission receipt = new Admission(lane, generation, sequence, callback);
        lastSequence = sequence;
        live.put(receipt, Boolean.TRUE);
        return receipt;
    }

    /** STOP revokes only ordinary callbacks. Pre-admitted lexical cleanup survives. */
    synchronized void sealOrdinary() {
        if (ordinarySealed) return;
        long next = Math.addExact(generation, 1L);
        generation = next;
        ordinarySealed = true;
    }

    /** Called only after the source-task cleanup/retirement boundary is closed. */
    synchronized void sealAll() {
        sealOrdinary();
        allSealed = true;
    }

    synchronized Snapshot snapshot() {
        int queued = 0;
        int running = 0;
        for (Admission receipt : live.keySet()) {
            if (receipt.state == State.QUEUED) queued++;
            if (receipt.state == State.RUNNING) running++;
        }
        return new Snapshot(generation, lastSequence, queued, running);
    }

    /**
     * A single-use queue receipt. close() accounts for an envelope discarded
     * before execution; it cannot retire a callback already executing.
     */
    final class Admission implements Runnable, AutoCloseable {
        private final Lane lane;
        private final long admittedGeneration;
        private final long sequence;
        // Protected by the ledger monitor. A retired/discarded receipt may
        // remain reachable through a foreign envelope or diagnostic handle;
        // clear its closure at terminal transition to avoid pinning actor
        // state long after the callback's execution lifetime has ended.
        private Runnable callback;
        private State state = State.QUEUED;

        private Admission(Lane lane, long admittedGeneration, long sequence,
                Runnable callback) {
            this.lane = lane;
            this.admittedGeneration = admittedGeneration;
            this.sequence = sequence;
            this.callback = callback;
        }

        long sequence() { return sequence; }
        long generation() { return admittedGeneration; }
        Lane lane() { return lane; }

        @Override
        public void run() {
            final Runnable admittedCallback;
            synchronized (ActorContinuationLedger.this) {
                if (state != State.QUEUED) {
                    throw new IllegalStateException("actor continuation receipt replay: " + sequence);
                }
                if (allSealed || (lane == Lane.ORDINARY
                        && (ordinarySealed || admittedGeneration != generation))) {
                    callback = null;
                    state = State.DISCARDED;
                    live.remove(this);
                    return;
                }
                admittedCallback = callback;
                // The executing stack is the sole callback owner from now on.
                // A foreign close() cannot retire this RUNNING receipt.
                callback = null;
                state = State.RUNNING;
            }
            try {
                admittedCallback.run();
            } finally {
                synchronized (ActorContinuationLedger.this) {
                    state = State.RETIRED;
                    live.remove(this);
                }
            }
        }

        @Override
        public void close() {
            synchronized (ActorContinuationLedger.this) {
                if (state == State.QUEUED) {
                    callback = null;
                    state = State.DISCARDED;
                    live.remove(this);
                }
                // A RUNNING receipt is physically in flight and may only be
                // retired by run()'s finally, never by a foreign cancellation.
            }
        }
    }
}
