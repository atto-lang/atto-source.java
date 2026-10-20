package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

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

    private record DeferredSchedulerFailure(long cleanupAttempt, Throwable failure) {
        DeferredSchedulerFailure {
            if (cleanupAttempt < 0) {
                throw new IllegalArgumentException("invalid deferred cleanup attempt");
            }
            Objects.requireNonNull(failure, "failure");
        }
    }

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
    // Misrouted cleanup callbacks must be able to report failure without
    // acquiring this monitor. A trusted host observation folds the deferred
    // violation into QUARANTINED under the normal session lock.
    private final AtomicReference<DeferredSchedulerFailure> deferredSchedulerFailure =
            new AtomicReference<>();

    IpcTransportSessionLease(IpcReceiptCorrelation.Envelope trustedEpoch,
                             long generation, int limit,
                             IpcReceiptCompletionBridge receipts,
                             FrameWriter writer, PhysicalCloser closer,
                             CleanupScheduler scheduler) {
        this(trustedEpoch, generation, limit, receipts, null,
                writer, closer, scheduler);
    }

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
        this.physicalOwner = physicalOwner;
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
            absorbDeferredSchedulerFailureLocked();
            if (phase != Phase.OPEN || expectedGeneration != generation
                    || !sameTrustedEpoch(message) || activeWrites >= limit) {
                throw new PreDispatchRejectedException(
                        "closed/stale/bounded IPC transport");
            }
            if (physicalOwner == null) {
                throw new IllegalStateException(
                        "atomic IPC admission requires bound physical owner");
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
     * Linearize session admission, receipt dispatch commitment, and close
     * intent on this session monitor, BEFORE any possible physical write.
     * The transport callback is invoked OUTSIDE both session and receipt
     * monitors while this operation pins the underlying physical resource.
     */
    void writeFrame(long expectedGeneration,
                    IpcReceiptCorrelation.Envelope message,
                    byte[] preparedFrame) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport write");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(preparedFrame, "preparedFrame");
        synchronized (this) {
            absorbDeferredSchedulerFailureLocked();
            if (phase != Phase.OPEN || expectedGeneration != generation
                    || !sameTrustedEpoch(message) || activeWrites >= limit) {
                throw new PreDispatchRejectedException(
                        "closed/stale/bounded IPC transport");
            }
            // No arbitrary user callbacks or memory allocation after
            // commitment until the writer has acquired the physical pin.
            ++activeWrites;
            try {
                if (physicalOwner == null) {
                    receipts.commitDispatch(message);
                } else {
                    receipts.commitDispatch(
                            physicalOwner, message, generation);
                }
            } catch (RuntimeException failure) {
                --activeWrites;
                throw new PreDispatchRejectedException(
                        "IPC receipt dispatch commitment rejected", failure);
            } catch (Error failure) {
                --activeWrites;
                throw failure;
            }
        }
        try {
            writer.write(message, preparedFrame);
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
        // Closing can synchronously acquire this monitor and enqueue cleanup.
        // Keep even nominally nonblocking host-supervisor lifecycle calls off
        // actor carriers so a misbehaving scheduler cannot park guest progress.
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport close request");
        boolean finalize;
        synchronized (this) {
            absorbDeferredSchedulerFailureLocked();
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
                absorbDeferredSchedulerFailureLocked();
                if (phase == Phase.RETIRED || phase == Phase.QUARANTINED) break;
                if (remainingNanos <= 0) return false;
                // A deferred actor-carrier scheduler violation is recorded
                // lock-free and cannot notify this monitor without risking the
                // actor park we are preventing. Bound host wait slices so the
                // supervisor observes it promptly without busy polling.
                long sliceNanos = Math.min(remainingNanos, 50_000_000L);
                long millis = sliceNanos / 1_000_000L;
                int nanos = (int) (sliceNanos % 1_000_000L);
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
            absorbDeferredSchedulerFailureLocked();
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
            absorbDeferredSchedulerFailureLocked();
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
            absorbDeferredSchedulerFailureLocked();
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
            absorbDeferredSchedulerFailureLocked();
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
        if (RuntimeBlockingGuard.actorCarrierMarked()) {
            deferSchedulerFailure(attempt, new IllegalStateException(
                    "cleanup scheduler executed on an actor carrier"));
            return;
        }
        synchronized (this) {
            absorbDeferredSchedulerFailureLocked();
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
        try {
            // Arbitrary dependent user callbacks can run: no session lock held.
            if (physicalOwner == null) {
                receipts.transportLost(trustedEpoch);
            } else {
                receipts.transportLost(physicalOwner, trustedEpoch, generation);
            }
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
    }

    /**
     * Misrouted actor-carrier callbacks report through this lock-free path so
     * they can never park behind a host lifecycle monitor.
     */
    void deferSchedulerFailure(long expectedCleanupAttempt, Throwable failure) {
        if (expectedCleanupAttempt <= 0) {
            throw new IllegalArgumentException("invalid cleanup attempt");
        }
        deferSchedulerFailureInternal(expectedCleanupAttempt, failure);
    }

    void deferExternalSchedulerFailure(Throwable failure) {
        deferSchedulerFailureInternal(0, failure);
    }

    private void deferSchedulerFailureInternal(long expectedCleanupAttempt, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!RuntimeBlockingGuard.actorCarrierMarked()) {
            throw new IllegalStateException(
                    "deferred scheduler failure requires an actor carrier");
        }
        var next = new DeferredSchedulerFailure(expectedCleanupAttempt, failure);
        deferredSchedulerFailure.getAndUpdate(current -> {
            if (current == null) return next;
            // External transport-abort scheduler failure is not tied to the
            // close-attempt counter and must not be displaced by stale cleanup.
            if (current.cleanupAttempt() == 0) return current;
            if (next.cleanupAttempt() == 0) return next;
            // Retain the newest cleanup attempt so an old callback cannot
            // poison an explicit retry.
            return next.cleanupAttempt() >= current.cleanupAttempt()
                    ? next : current;
        });
    }

    /** Only called while holding this monitor on a trusted host thread. */
    private void absorbDeferredSchedulerFailureLocked() {
        DeferredSchedulerFailure deferred = deferredSchedulerFailure.getAndSet(null);
        if (deferred == null || phase == Phase.RETIRED || phase == Phase.QUARANTINED) return;
        if (deferred.cleanupAttempt() != 0
                && deferred.cleanupAttempt() != cleanupAttempt) {
            return; // stale scheduler callback from an older cleanup attempt
        }
        if (closeFailure == null) closeFailure = deferred.failure();
        cleanupExecuting = false;
        phase = Phase.QUARANTINED;
        notifyAll();
    }

    synchronized Snapshot snapshot() {
        absorbDeferredSchedulerFailureLocked();
        return new Snapshot(generation, phase, activeWrites,
                physicalCloseConfirmed, receiptsRetired);
    }

    synchronized Throwable closeFailure() {
        absorbDeferredSchedulerFailureLocked();
        return closeFailure;
    }

    synchronized boolean invariant() {
        absorbDeferredSchedulerFailureLocked();
        return activeWrites >= 0 && activeWrites <= limit
                && (!physicalCloseConfirmed || activeWrites == 0)
                && (phase != Phase.RETIRED || (physicalCloseConfirmed && receiptsRetired))
                && (phase != Phase.OPEN || !physicalCloseConfirmed)
                && (phase != Phase.FINALIZING || activeWrites == 0)
                && (!cleanupExecuting || phase == Phase.FINALIZING);
    }
}
