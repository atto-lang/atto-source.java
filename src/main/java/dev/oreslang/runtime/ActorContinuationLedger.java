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
        return admit(lane, callback, null);
    }

    /**
     * Trusted-code image continuation receipt. The passed lease is transferred
     * to this ledger only on successful admission. A rejected admission leaves
     * its lease owned by the caller, which must close it in a finally block.
     * Queued STOP/discard, normal return, and exceptional callback completion
     * all release the same exact-image lease after physical callback exit.
     */
    synchronized Admission admit(Lane lane, Runnable callback,
            AutoCloseable codeLease) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(callback, "callback");
        if (allSealed || (lane == Lane.ORDINARY && ordinarySealed)) {
            throw new IllegalStateException("actor continuation ledger admission sealed");
        }
        if (live.size() >= capacity) {
            throw new IllegalStateException("actor continuation ledger at capacity");
        }
        long sequence = Math.addExact(lastSequence, 1L);
        Admission receipt = new Admission(lane, generation, sequence, callback, codeLease);
        lastSequence = sequence;
        live.put(receipt, Boolean.TRUE);
        return receipt;
    }

    /** External host-resource release happens after dropping this ledger monitor. */
    private static void releaseTerminal(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("owned continuation resource release failed", failure);
        }
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
        // Exact physical code image pinned before publication. Never release
        // while RUNNING even if a foreign cancellation closes this receipt.
        private AutoCloseable codeLease;
        private State state = State.QUEUED;

        private Admission(Lane lane, long admittedGeneration, long sequence,
                Runnable callback, AutoCloseable codeLease) {
            this.lane = lane;
            this.admittedGeneration = admittedGeneration;
            this.sequence = sequence;
            this.callback = callback;
            this.codeLease = codeLease;
        }

        long sequence() { return sequence; }
        long generation() { return admittedGeneration; }
        Lane lane() { return lane; }

        @Override
        public void run() {
            final Runnable admittedCallback;
            final AutoCloseable discardedCodeLease;
            synchronized (ActorContinuationLedger.this) {
                if (state != State.QUEUED) {
                    throw new IllegalStateException("actor continuation receipt replay: " + sequence);
                }
                if (allSealed || (lane == Lane.ORDINARY
                        && (ordinarySealed || admittedGeneration != generation))) {
                    callback = null;
                    state = State.DISCARDED;
                    live.remove(this);
                    discardedCodeLease = codeLease;
                    codeLease = null;
                    admittedCallback = null;
                } else {
                    admittedCallback = callback;
                    // The executing stack is the sole callback owner from now on.
                    // A foreign close() cannot retire this RUNNING receipt.
                    callback = null;
                    state = State.RUNNING;
                    discardedCodeLease = null;
                }
            }
            // Never acquire the code-loader monitor while holding the actor
            // ledger monitor. A STOP-vs-cleanup race must not deadlock.
            if (admittedCallback == null) {
                if (discardedCodeLease != null) releaseTerminal(discardedCodeLease);
                return;
            }
            Throwable callbackFailure = null;
            try {
                admittedCallback.run();
            } catch (RuntimeException | Error failure) {
                callbackFailure = failure;
                throw failure;
            } finally {
                final AutoCloseable completedCodeLease;
                synchronized (ActorContinuationLedger.this) {
                    state = State.RETIRED;
                    live.remove(this);
                    completedCodeLease = codeLease;
                    codeLease = null;
                }
                // Preserve the exact guest callback failure as primary; a
                // failed owned-resource drop must remain visible as suppressed
                // evidence instead of replacing that failure on unwind.
                try {
                    releaseTerminal(completedCodeLease);
                } catch (RuntimeException | Error releaseFailure) {
                    if (callbackFailure == null) throw releaseFailure;
                    if (callbackFailure != releaseFailure)
                        callbackFailure.addSuppressed(releaseFailure);
                }
            }
        }

        @Override
        public void close() {
            final AutoCloseable discardedCodeLease;
            synchronized (ActorContinuationLedger.this) {
                if (state == State.QUEUED) {
                    callback = null;
                    state = State.DISCARDED;
                    live.remove(this);
                    discardedCodeLease = codeLease;
                    codeLease = null;
                } else {
                    // A RUNNING receipt is physically in flight and may only
                    // be retired by run()'s finally, never foreign cancellation.
                    discardedCodeLease = null;
                }
            }
            if (discardedCodeLease != null) releaseTerminal(discardedCodeLease);
        }
    }
}
