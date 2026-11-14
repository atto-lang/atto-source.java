package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Trusted host-side ownership of one IPC transport incarnation.
 *
 * This is NOT a guest-visible capability, peer authenticator, socket
 * implementation or zero-copy ABI. The caller supplies an independently
 * authenticated, synchronous host frame writer and a physical close callback.
 * Never dispatch on an actor carrier or route UNTRUSTED NETWORK through here.
 * Synchronous send/write/wait entry points enforce that carrier boundary before
 * receipt mutation, writer invocation, or monitor acquisition for blocking waits.
 * The authenticated socket adapter repeats the outer check, while this lease
 * is the non-bypassable package-local enforcement point.
 */
final class IpcTransportSessionLease {
    enum Phase { OPEN, CLOSING, FINALIZING, RETIRED, QUARANTINED }

    @FunctionalInterface interface FrameWriter {
        /**
         * Bound physical writer. The prepared authenticated frame is data,
         * never authority; the writer identity itself cannot vary per send.
         * Must not return before the physical write attempt has terminated.
         */
        void write(IpcReceiptCorrelation.Envelope message, byte[] preparedFrame)
                throws IOException;
    }

    static final class PreDispatchRejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        PreDispatchRejectedException(String message) { super(message); }
        PreDispatchRejectedException(String message, Throwable cause) { super(message, cause); }
    }

    record WriteAttempt(
            CompletionStage<IpcReceiptCorrelation.Stage> outcome,
            Throwable error) {
        WriteAttempt {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    @FunctionalInterface interface PhysicalCloser {
        /** Return only if the physical session is closed and reuse is safe. */
        void closeAndConfirm() throws Exception;
    }

    @FunctionalInterface interface CleanupScheduler {
        /** Bounded, nonparking, never caller-runs; host-owned worker only. */
        void enqueue(Runnable cleanup);
    }

    record Snapshot(long generation, Phase phase, int activeWrites,
                    boolean physicalCloseConfirmed, boolean receiptsRetired) { }

    private final IpcReceiptCorrelation.Envelope trustedEpoch;
    private final long generation;
    private final int limit;
    private final IpcReceiptCompletionBridge receipts;
    private final Object physicalOwner;
    private final FrameWriter writer;
    private final PhysicalCloser closer;
    private final CleanupScheduler scheduler;
    private Phase phase = Phase.OPEN;
    private int activeWrites;
    private boolean physicalCloseConfirmed;
    private boolean receiptsRetired;
    private Throwable closeFailure;
    private long cleanupAttempt;
    private boolean cleanupExecuting;
    private long enqueueAttempt;
    private Thread enqueueCaller;

    IpcTransportSessionLease(IpcReceiptCorrelation.Envelope trustedEpoch,
                             long generation, int limit,
                             IpcReceiptCompletionBridge receipts,
                             Object physicalOwner,
                             FrameWriter writer, PhysicalCloser closer,
                             CleanupScheduler scheduler) {
        if (generation <= 0 || limit <= 0 || limit > 65536) {
            throw new IllegalArgumentException("invalid transport session policy");
        }
        this.trustedEpoch = Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        this.generation = generation;
        this.limit = limit;
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.physicalOwner = Objects.requireNonNull(
                physicalOwner, "physicalOwner");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    long generation() { return generation; }

    /** A stale actor/route/grant cannot send over a reused session name. */
    private boolean sameTrustedEpoch(IpcReceiptCorrelation.Envelope message) {
        return trustedEpoch.domain().equals(message.domain())
                && trustedEpoch.sender().equals(message.sender())
                && trustedEpoch.destination().equals(message.destination())
                && trustedEpoch.session().equals(message.session())
                && trustedEpoch.senderIncarnation() == message.senderIncarnation()
                && trustedEpoch.destinationIncarnation() == message.destinationIncarnation()
                && trustedEpoch.routeEpoch() == message.routeEpoch()
                && trustedEpoch.capabilityGeneration() == message.capabilityGeneration();
    }

    /**
     * Owner-bound adapter path. OPEN/generation/epoch validation, correlation
     * admission, dispatch commitment and physical write pinning are one
     * lifecycle. No intermediate token can be abandoned after commitment.
     */
    WriteAttempt sendPreparedFrame(
            long expectedGeneration,
            IpcReceiptCorrelation.Envelope message,
            byte[] preparedFrame) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport send");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(preparedFrame, "preparedFrame");
        final CompletionStage<IpcReceiptCorrelation.Stage> outcome;
        synchronized (this) {
            if (phase != Phase.OPEN || expectedGeneration != generation
                    || !sameTrustedEpoch(message) || activeWrites >= limit) {
                throw new PreDispatchRejectedException(
                        "closed/stale/bounded IPC transport");
            }
            outcome = receipts.admitAndCommit(
                    physicalOwner, message, generation);
            ++activeWrites;
        }

        Throwable error = null;
        try {
            writer.write(message, preparedFrame);
        } catch (IOException | RuntimeException failure) {
            error = failure;
            requestClose();
        } catch (Error failure) {
            requestClose();
            throw failure;
        } finally {
            releaseWrite();
        }
        return new WriteAttempt(outcome, error);
    }

    /**
     * Close intent does not wait for writes or run physical cleanup inline.
     * An admitted last-writer release submits cleanup to a bounded host
     * worker; on scheduler failure the session stays quarantined.
     */
    void requestClose() {
        // Closing can synchronously acquire this monitor and enqueue cleanup.
        // Keep even nominally nonblocking host-supervisor lifecycle calls off
        // actor carriers so a misbehaving scheduler cannot park guest progress.
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport close request");
        boolean finalize;
        synchronized (this) {
            if (phase == Phase.OPEN) phase = Phase.CLOSING;
            finalize = beginFinalization();
        }
        if (finalize) scheduleFinalization();
    }

    /**
     * Host-supervisor bounded wait. This never changes receipt semantics:
     * RETIRED means confirmed close + receipt retirement, QUARANTINED is a
     * fail-closed terminal-for-now state, and timeout never implies success.
     */
    boolean awaitTerminal(long timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis < 0 || timeoutMillis > 300_000) {
            throw new IllegalArgumentException("invalid IPC close wait");
        }
        // Check before acquiring the session monitor: an actor carrier must
        // never park merely trying to enter this blocking wait.
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport terminal wait");
        synchronized (this) {
            long remainingNanos = timeoutMillis * 1_000_000L;
            long deadline = System.nanoTime() + remainingNanos;
            while (phase != Phase.RETIRED && phase != Phase.QUARANTINED) {
                if (remainingNanos <= 0) return false;
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                wait(millis, nanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return phase == Phase.RETIRED;
        }
    }

    /**
     * Fail closed when a trusted abort task cannot be admitted or when the
     * write pins do not drain by a supervisor deadline. Never marks an
     * executing physical close as retired or interrupts its cleanup worker.
     */
    boolean quarantineIfUnsettled(Throwable failure) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport quarantine");
        Objects.requireNonNull(failure, "failure");
        synchronized (this) {
            if (phase == Phase.RETIRED || phase == Phase.QUARANTINED) return false;
            if (phase == Phase.FINALIZING && cleanupExecuting) {
                if (closeFailure == null) closeFailure = failure;
                return false;
            }
            closeFailure = failure;
            phase = Phase.QUARANTINED;
            notifyAll();
            return true;
        }
    }

    /** An explicit supervisor retry never reopens a quarantined session. */
    void retryQuarantinedClose() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport quarantined close retry");
        synchronized (this) {
            if (phase != Phase.QUARANTINED || activeWrites != 0) {
                throw new IllegalStateException("transport not quarantined");
            }
            phase = Phase.FINALIZING;
        }
        scheduleFinalization();
    }

    private void releaseWrite() {
        boolean finalize;
        synchronized (this) {
            if (activeWrites <= 0) throw new IllegalStateException("IPC write pin underflow");
            --activeWrites;
            finalize = beginFinalization();
        }
        if (finalize) scheduleFinalization();
    }

    /** Only called while holding this monitor. */
    private boolean beginFinalization() {
        if (phase == Phase.CLOSING && activeWrites == 0) {
            phase = Phase.FINALIZING;
            return true;
        }
        return false;
    }

    private void scheduleFinalization() {
        final long attempt;
        synchronized (this) {
            if (phase != Phase.FINALIZING) return;
            if (cleanupAttempt == Long.MAX_VALUE) {
                closeFailure = new IllegalStateException("cleanup attempt overflow");
                phase = Phase.QUARANTINED;
                notifyAll();
                return;
            }
            attempt = ++cleanupAttempt;
        }
        Thread caller = Thread.currentThread();
        synchronized (this) {
            enqueueAttempt = attempt;
            enqueueCaller = caller;
        }
        try {
            scheduler.enqueue(() -> finalizePhysicalClose(attempt));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                if (phase == Phase.FINALIZING && cleanupAttempt == attempt
                        && !cleanupExecuting) {
                    closeFailure = failure;
                    phase = Phase.QUARANTINED;
                    notifyAll();
                }
            }
        } finally {
            synchronized (this) {
                if (enqueueAttempt == attempt && enqueueCaller == caller) {
                    enqueueCaller = null;
                }
            }
        }
    }

    private void finalizePhysicalClose(long attempt) {
        // The scheduler callback itself may have been misrouted onto an actor
        // carrier. Reject before touching this monitor: checking only after
        // synchronized(this) could park the actor behind a host lifecycle lock.
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport cleanup worker");
        synchronized (this) {
            if (phase != Phase.FINALIZING || cleanupAttempt != attempt
                    || cleanupExecuting) return;
            if (enqueueAttempt == attempt && enqueueCaller == Thread.currentThread()) {
                closeFailure = new IllegalStateException(
                        "cleanup scheduler executed inline on requesting carrier");
                enqueueCaller = null;
                phase = Phase.QUARANTINED;
                notifyAll();
                return;
            }
            cleanupExecuting = true;
        }
        try {
            if (!physicalCloseConfirmed) closer.closeAndConfirm();
        } catch (Exception | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                cleanupExecuting = false;
                phase = Phase.QUARANTINED;
                notifyAll();
            }
            return; // Never declare the descriptor reusable.
        }
        synchronized (this) { physicalCloseConfirmed = true; }
        final IpcReceiptCompletionBridge.RetiredCompletions terminal;
        try {
            // Retire correlation authority without running arbitrary dependent
            // callbacks: they may synchronously block this cleanup worker.
            terminal = receipts.transportLostDeferred(
                    physicalOwner, trustedEpoch, generation);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                cleanupExecuting = false;
                phase = Phase.QUARANTINED;
                notifyAll();
            }
            return;
        }
        synchronized (this) {
            receiptsRetired = true;
            closeFailure = null;
            cleanupExecuting = false;
            phase = Phase.RETIRED;
            notifyAll();
        }
        // All physical/ledger state is now settled and published. Deliver
        // terminal stages outside the lease lock so a stalled application
        // callback cannot hold the descriptor in FINALIZING indefinitely.
        terminal.complete();
    }

    Snapshot snapshot() {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport snapshot");
        synchronized (this) {
            return new Snapshot(generation, phase, activeWrites,
                    physicalCloseConfirmed, receiptsRetired);
        }
    }

    Throwable closeFailure() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport close failure inspection");
        synchronized (this) {
            return closeFailure;
        }
    }

    boolean invariant() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport invariant inspection");
        synchronized (this) {
            return activeWrites >= 0 && activeWrites <= limit
                    && (!physicalCloseConfirmed || activeWrites == 0)
                    && (phase != Phase.RETIRED || (physicalCloseConfirmed && receiptsRetired))
                    && (phase != Phase.OPEN || !physicalCloseConfirmed)
                    && (phase != Phase.FINALIZING || activeWrites == 0)
                    && (!cleanupExecuting || phase == Phase.FINALIZING);
        }
    }
}
