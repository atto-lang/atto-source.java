package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Trusted host-only transport session lifetime gate. This protects host
 * dispatch claims from close/reincarnation while a physical adapter owns
 * outstanding I/O. It neither authenticates peers nor performs socket I/O.
 *
 * An adapter MUST complete a send lease only after all OS writes/queued
 * callbacks using its descriptor are quiescent. Merely cancelling a guest
 * Future or requesting close is not physical quiescence.
 *
 * Lock order is this gate -> completion bridge -> receipt correlation.
 * Neither network I/O nor guest completion callbacks execute inside gate.
 */
final class IpcTrustedSendSession {
    private final Object gate = new Object();
    private final IpcReceiptCompletionBridge receipts;
    private final IpcReceiptCorrelation.Envelope epoch;
    private final int maxOutstanding;
    private int outstanding;
    private boolean closing;
    private boolean finalizationStarted;
    private boolean finalizationComplete;

    IpcTrustedSendSession(IpcReceiptCompletionBridge receipts,
                          IpcReceiptCorrelation.Envelope authenticatedEpoch,
                          int maxOutstanding) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.epoch = Objects.requireNonNull(authenticatedEpoch, "authenticatedEpoch");
        if (maxOutstanding < 1 || maxOutstanding > 65536) {
            throw new IllegalArgumentException("invalid bounded send-lease capacity");
        }
        this.maxOutstanding = maxOutstanding;
    }

    private boolean sameEpoch(IpcReceiptCorrelation.Envelope other) {
        return epoch.domain().equals(other.domain())
                && epoch.sender().equals(other.sender())
                && epoch.destination().equals(other.destination())
                && epoch.session().equals(other.session())
                && epoch.senderIncarnation() == other.senderIncarnation()
                && epoch.destinationIncarnation() == other.destinationIncarnation()
                && epoch.routeEpoch() == other.routeEpoch()
                && epoch.capabilityGeneration() == other.capabilityGeneration();
    }

    /**
     * Preallocates the lease before the irreversible receipt commitment.
     * This is only a host-side publication permit. The trusted physical
     * adapter must retain this object until all actual I/O is complete.
     */
    SendLease begin(IpcReceiptCorrelation.Envelope message) {
        Objects.requireNonNull(message, "message");
        SendLease lease = new SendLease(this);
        synchronized (gate) {
            if (closing || finalizationStarted) {
                throw new IllegalStateException("transport session closing");
            }
            if (!sameEpoch(message)) {
                throw new IllegalArgumentException("stale transport generation or identity");
            }
            if (outstanding >= maxOutstanding) {
                throw new IllegalStateException("bounded transport permits exhausted");
            }
            receipts.commitDispatch(message);
            ++outstanding;
            return lease;
        }
    }

    void requestClose() {
        synchronized (gate) {
            closing = true;
            // Physical I/O may remain outstanding; no guest callbacks here.
        }
    }

    /**
     * Finalize only when the trusted adapter independently knows the
     * underlying descriptor/OS transport has closed AND all outstanding
     * leases have completed physical quiescence. This does not prove that
     * physical closure by itself: the caller owns that external evidence.
     *
     * Mark finalization-before-notifying to avoid reentrant double-close.
     * On a host failure quarantine this session; do not silently retry or
     * reuse the generation until the bridge is independently reconciled.
     */
    int confirmPhysicalClosed() {
        synchronized (gate) {
            if (!closing || outstanding != 0 || finalizationStarted) {
                throw new IllegalStateException("physical transport not ready for retirement");
            }
            finalizationStarted = true;
        }
        // Host Futures may execute arbitrary callbacks, never hold gate here.
        int uncertain = receipts.transportLost(epoch);
        synchronized (gate) {
            finalizationComplete = true;
        }
        return uncertain;
    }

    Snapshot snapshot() {
        synchronized (gate) {
            return new Snapshot(outstanding, closing, finalizationStarted,
                    finalizationComplete, maxOutstanding);
        }
    }

    private void release(SendLease lease) {
        synchronized (gate) {
            if (lease.owner != this || lease.released) {
                throw new IllegalArgumentException("foreign or double send lease release");
            }
            if (outstanding <= 0 || finalizationStarted) {
                throw new IllegalStateException("send lease accounting retired");
            }
            lease.released = true;
            --outstanding;
        }
    }

    record Snapshot(int outstanding, boolean closing,
                    boolean finalizationStarted, boolean finalizationComplete,
                    int maxOutstanding) { }

    final static class SendLease {
        private final IpcTrustedSendSession owner;
        private boolean released;

        private SendLease(IpcTrustedSendSession owner) {
            this.owner = owner;
        }

        /** Trusted callback: physical write/flush/cancel/close has drained. */
        void releaseAfterPhysicalQuiescence() {
            owner.release(this);
        }
    }
}
