package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;

/**
 * Trusted host-side ownership of one IPC transport incarnation.
 *
 * This is NOT a guest-visible capability, peer authenticator, socket
 * implementation or zero-copy ABI. The caller supplies an independently
 * authenticated, synchronous host frame writer and a physical close callback.
 * Never dispatch on an actor carrier or route UNTRUSTED NETWORK through here.
 */
final class IpcTransportSessionLease {
    enum Phase { OPEN, CLOSING, FINALIZING, RETIRED, QUARANTINED }

    @FunctionalInterface interface FrameWriter {
        /** Must not return before the physical write attempt has terminated. */
        void write(IpcReceiptCorrelation.Envelope message) throws IOException;
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
    private final PhysicalCloser closer;
    private final CleanupScheduler scheduler;
    private Phase phase = Phase.OPEN;
    private int activeWrites;
    private boolean physicalCloseConfirmed;
    private boolean receiptsRetired;
    private Throwable closeFailure;
    private long cleanupAttempt;
    private boolean cleanupExecuting;

    IpcTransportSessionLease(IpcReceiptCorrelation.Envelope trustedEpoch,
                             long generation, int limit,
                             IpcReceiptCompletionBridge receipts,
                             PhysicalCloser closer, CleanupScheduler scheduler) {
        if (generation <= 0 || limit <= 0 || limit > 65536) {
            throw new IllegalArgumentException("invalid transport session policy");
        }
        this.trustedEpoch = Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        this.generation = generation;
        this.limit = limit;
        this.receipts = Objects.requireNonNull(receipts, "receipts");
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
     * Linearize session admission, receipt dispatch commitment, and close
     * intent on this session monitor, BEFORE any possible physical write.
     * The transport callback is invoked OUTSIDE both session and receipt
     * monitors while this operation pins the underlying physical resource.
     */
    void writeFrame(long expectedGeneration, IpcReceiptCorrelation.Envelope message,
                    FrameWriter writer) throws IOException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(writer, "writer");
        synchronized (this) {
            if (phase != Phase.OPEN || expectedGeneration != generation
                    || !sameTrustedEpoch(message) || activeWrites >= limit) {
                throw new IllegalStateException("closed/stale/bounded IPC transport");
            }
            // No arbitrary user callbacks or memory allocation after
            // commitment until the writer has acquired the physical pin.
            ++activeWrites;
            try {
                receipts.commitDispatch(message);
            } catch (RuntimeException | Error failure) {
                --activeWrites;
                throw failure;
            }
        }
        try {
            writer.write(message);
        } catch (IOException | RuntimeException | Error failure) {
            // A dispatch-committed failed write is INDETERMINATE, even when
            // the host call threw before reporting any accepted bytes.
            requestClose();
            throw failure;
        } finally {
            releaseWrite();
        }
    }

    /**
     * Close intent does not wait for writes or run physical cleanup inline.
     * An admitted last-writer release submits cleanup to a bounded host
     * worker; on scheduler failure the session stays quarantined.
     */
    void requestClose() {
        boolean finalize;
        synchronized (this) {
            if (phase == Phase.OPEN) phase = Phase.CLOSING;
            finalize = beginFinalization();
        }
        if (finalize) scheduleFinalization();
    }

    /** An explicit supervisor retry never reopens a quarantined session. */
    void retryQuarantinedClose() {
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
                return;
            }
            attempt = ++cleanupAttempt;
        }
        try {
            scheduler.enqueue(() -> finalizePhysicalClose(attempt));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                if (phase == Phase.FINALIZING && cleanupAttempt == attempt
                        && !cleanupExecuting) {
                    closeFailure = failure;
                    phase = Phase.QUARANTINED;
                }
            }
        }
    }

    private void finalizePhysicalClose(long attempt) {
        synchronized (this) {
            if (phase != Phase.FINALIZING || cleanupAttempt != attempt
                    || cleanupExecuting) return;
            cleanupExecuting = true;
        }
        try {
            if (!physicalCloseConfirmed) closer.closeAndConfirm();
        } catch (Exception | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                cleanupExecuting = false;
                phase = Phase.QUARANTINED;
            }
            return; // Never declare the descriptor reusable.
        }
        synchronized (this) { physicalCloseConfirmed = true; }
        try {
            // Arbitrary dependent user callbacks can run: no session lock held.
            receipts.transportLost(trustedEpoch);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                cleanupExecuting = false;
                phase = Phase.QUARANTINED;
            }
            return;
        }
        synchronized (this) {
            receiptsRetired = true;
            closeFailure = null;
            cleanupExecuting = false;
            phase = Phase.RETIRED;
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(generation, phase, activeWrites,
                physicalCloseConfirmed, receiptsRetired);
    }

    synchronized Throwable closeFailure() { return closeFailure; }

    synchronized boolean invariant() {
        return activeWrites >= 0 && activeWrites <= limit
                && (!physicalCloseConfirmed || activeWrites == 0)
                && (phase != Phase.RETIRED || (physicalCloseConfirmed && receiptsRetired))
                && (phase != Phase.OPEN || !physicalCloseConfirmed)
                && (phase != Phase.FINALIZING || activeWrites == 0)
                && (!cleanupExecuting || phase == Phase.FINALIZING);
    }
}
