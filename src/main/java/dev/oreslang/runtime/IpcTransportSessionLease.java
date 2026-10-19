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

    record Snapshot(long generation, Phase phase, int activeWrites,
                    boolean physicalCloseConfirmed, boolean receiptsRetired) { }

    private final String session;
    private final long generation;
    private final int limit;
    private final IpcReceiptCompletionBridge receipts;
    private final PhysicalCloser closer;
    private Phase phase = Phase.OPEN;
    private int activeWrites;
    private boolean physicalCloseConfirmed;
    private boolean receiptsRetired;
    private Throwable closeFailure;

    IpcTransportSessionLease(String session, long generation, int limit,
                             IpcReceiptCompletionBridge receipts,
                             PhysicalCloser closer) {
        if (session == null || session.isBlank() || session.length() > 96
                || generation <= 0 || limit <= 0 || limit > 65536) {
            throw new IllegalArgumentException("invalid transport session policy");
        }
        this.session = session;
        this.generation = generation;
        this.limit = limit;
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    long generation() { return generation; }

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
                    || !session.equals(message.session()) || activeWrites >= limit) {
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

    /** Non-parking close intent. Physical close runs only after all pins drain. */
    void requestClose() {
        boolean finalize;
        synchronized (this) {
            if (phase == Phase.OPEN) phase = Phase.CLOSING;
            finalize = beginFinalization();
        }
        if (finalize) finalizePhysicalClose();
    }

    private void releaseWrite() {
        boolean finalize;
        synchronized (this) {
            if (activeWrites <= 0) throw new IllegalStateException("IPC write pin underflow");
            --activeWrites;
            finalize = beginFinalization();
        }
        if (finalize) finalizePhysicalClose();
    }

    /** Only called while holding this monitor. */
    private boolean beginFinalization() {
        if (phase == Phase.CLOSING && activeWrites == 0) {
            phase = Phase.FINALIZING;
            return true;
        }
        return false;
    }

    private void finalizePhysicalClose() {
        try {
            closer.closeAndConfirm();
        } catch (Exception | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                phase = Phase.QUARANTINED;
            }
            return; // Never declare the descriptor reusable.
        }
        synchronized (this) { physicalCloseConfirmed = true; }
        try {
            // Arbitrary dependent user callbacks can run: no session lock held.
            receipts.transportLost(session);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                closeFailure = failure;
                phase = Phase.QUARANTINED;
            }
            return;
        }
        synchronized (this) {
            receiptsRetired = true;
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
                && (phase != Phase.FINALIZING || activeWrites == 0);
    }
}
