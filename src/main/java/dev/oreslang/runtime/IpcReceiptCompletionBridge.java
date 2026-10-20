package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Trusted host adapter between one bounded receipt table and terminal host
 * completion stages. NOT a network authenticator or an Oreslang guest Future.
 *
 * Never runs arbitrary CompletableFuture completion callbacks with its gate
 * held. Source actor scheduling must separately project results onto CONTROL.
 */
public final class IpcReceiptCompletionBridge {
    private final Object gate = new Object();
    private final IpcReceiptCorrelation correlations;
    private final Map<Long, CompletableFuture<IpcReceiptCorrelation.Stage>> completions =
            new HashMap<>();
    // A production physical transport may claim this bridge exactly once.
    // Never clear/rebind these fields: reconnect must allocate a fresh bridge,
    // preventing delayed generation-N close from retiring generation-N+1 work.
    private Object physicalOwner;
    private IpcReceiptCorrelation.Envelope physicalEpoch;
    private long physicalGeneration;

    public IpcReceiptCompletionBridge(int capacity) {
        correlations = new IpcReceiptCorrelation(capacity);
    }

    /**
     * Bind this empty bridge to exactly one physical transport incarnation.
     * The owner is an opaque host object identity, never guest data. This
     * binding is intentionally irreversible even after retirement.
     */
    void claimPhysicalTransport(Object owner,
                                IpcReceiptCorrelation.Envelope trustedEpoch,
                                long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC physical receipt-bridge binding");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        if (generation <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        synchronized (gate) {
            if (physicalOwner != null) {
                throw new IllegalStateException("IPC receipt bridge already bound");
            }
            if (!completions.isEmpty() || correlations.counters().pending() != 0) {
                throw new IllegalStateException(
                        "IPC receipt bridge must be empty before physical binding");
            }
            physicalOwner = owner;
            physicalEpoch = trustedEpoch;
            physicalGeneration = generation;
        }
    }

    private static boolean sameTransportEpoch(
            IpcReceiptCorrelation.Envelope a,
            IpcReceiptCorrelation.Envelope b) {
        return a.domain().equals(b.domain())
                && a.sender().equals(b.sender())
                && a.destination().equals(b.destination())
                && a.session().equals(b.session())
                && a.senderIncarnation() == b.senderIncarnation()
                && a.destinationIncarnation() == b.destinationIncarnation()
                && a.routeEpoch() == b.routeEpoch()
                && a.capabilityGeneration() == b.capabilityGeneration();
    }

    private void requirePhysicalTransport(
            Object owner,
            IpcReceiptCorrelation.Envelope trustedEpoch,
            long generation) {
        if (physicalOwner == null || physicalOwner != owner
                || physicalGeneration != generation
                || !sameTransportEpoch(physicalEpoch, trustedEpoch)) {
            throw new IllegalArgumentException(
                    "foreign/stale IPC physical transport authority");
        }
    }

    private void requireUnboundPhysicalBridge() {
        if (physicalOwner != null) {
            throw new IllegalStateException(
                    "bound IPC bridge requires physical owner/generation");
        }
    }

    public CompletionStage<IpcReceiptCorrelation.Stage> admit(
            IpcReceiptCorrelation.Envelope envelope) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge admission");
        Objects.requireNonNull(envelope, "envelope");
        // Prepare all callback bookkeeping before admitting the DATA receipt.
        CompletableFuture<IpcReceiptCorrelation.Stage> future = new CompletableFuture<>();
        CompletionStage<IpcReceiptCorrelation.Stage> publicView = future.minimalCompletionStage();
        synchronized (gate) {
            requireUnboundPhysicalBridge();
            if (completions.containsKey(envelope.messageSequence())) {
                throw new IllegalArgumentException("duplicate host correlation");
            }
            completions.put(envelope.messageSequence(), future);
            try {
                correlations.admit(envelope);
            } catch (RuntimeException | Error e) {
                completions.remove(envelope.messageSequence());
                throw e;
            }
        }
        return publicView;
    }

    CompletionStage<IpcReceiptCorrelation.Stage> admit(
            Object owner,
            IpcReceiptCorrelation.Envelope envelope,
            long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge admission");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<IpcReceiptCorrelation.Stage> future = new CompletableFuture<>();
        CompletionStage<IpcReceiptCorrelation.Stage> publicView =
                future.minimalCompletionStage();
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            if (completions.containsKey(envelope.messageSequence())) {
                throw new IllegalArgumentException("duplicate host correlation");
            }
            completions.put(envelope.messageSequence(), future);
            try {
                correlations.admit(envelope);
            } catch (RuntimeException | Error e) {
                completions.remove(envelope.messageSequence());
                throw e;
            }
        }
        return publicView;
    }

    CompletionStage<IpcReceiptCorrelation.Stage> admitAndCommit(
            Object owner,
            IpcReceiptCorrelation.Envelope envelope,
            long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge atomic admission");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<IpcReceiptCorrelation.Stage> future = new CompletableFuture<>();
        CompletionStage<IpcReceiptCorrelation.Stage> publicView =
                future.minimalCompletionStage();
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            if (completions.containsKey(envelope.messageSequence())) {
                throw new IllegalArgumentException("duplicate host correlation");
            }
            completions.put(envelope.messageSequence(), future);
            boolean admitted = false;
            try {
                correlations.admit(envelope);
                admitted = true;
                correlations.commitDispatch(envelope);
            } catch (RuntimeException | Error failure) {
                completions.remove(envelope.messageSequence());
                if (admitted) {
                    try {
                        correlations.rejectBeforeRemote(envelope);
                    } catch (RuntimeException | Error rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        }
        return publicView;
    }

    /**
     * Trusted transport publication fence. Caller MUST validate the same
     * physical transport session and hold its generation/close ownership
     * through actual send; the receipt ledger does not own socket I/O.
     * If send can fail after this returns, retire on trusted session loss:
     * never call rejectBeforeRemote after dispatch commitment.
     */
    void commitDispatch(IpcReceiptCorrelation.Envelope envelope) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge dispatch");
        Objects.requireNonNull(envelope, "envelope");
        synchronized (gate) {
            requireUnboundPhysicalBridge();
            if (!completions.containsKey(envelope.messageSequence())) {
                throw new IllegalArgumentException("missing IPC dispatch correlation");
            }
            correlations.commitDispatch(envelope);
        }
    }

    void commitDispatch(Object owner,
                        IpcReceiptCorrelation.Envelope envelope,
                        long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge dispatch");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            if (!completions.containsKey(envelope.messageSequence())) {
                throw new IllegalArgumentException("missing IPC dispatch correlation");
            }
            correlations.commitDispatch(envelope);
        }
    }

    /** Caller MUST verify the remote peer/frame/capability before this call. */
    public void acceptVerified(IpcReceiptCorrelation.Envelope envelope,
                               IpcReceiptCorrelation.Stage stage) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge verified transition");
        CompletableFuture<IpcReceiptCorrelation.Stage> done = null;
        synchronized (gate) {
            requireUnboundPhysicalBridge();
            correlations.acceptVerified(envelope, stage);
            if (stage != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                done = completions.remove(envelope.messageSequence());
                if (done == null) throw new IllegalStateException("missing host completion");
            }
        }
        // An arbitrary dependent callback may synchronously reenter the actor:
        // never invoke it from within the admission/correlation monitor.
        if (done != null) done.complete(stage);
    }

    /**
     * Deferred publication token for a verified terminal receipt.
     *
     * The receipt state transition is already committed when this object is
     * returned. publish() may run arbitrary dependent callbacks, so callers
     * must invoke it only after releasing any outer transport/order lock.
     */
    static final class DeferredVerifiedCompletion {
        private final CompletableFuture<IpcReceiptCorrelation.Stage> done;
        private final IpcReceiptCorrelation.Stage stage;

        private DeferredVerifiedCompletion(
                CompletableFuture<IpcReceiptCorrelation.Stage> done,
                IpcReceiptCorrelation.Stage stage) {
            this.done = Objects.requireNonNull(done, "done");
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        void publish() {
            done.complete(stage);
        }
    }

    DeferredVerifiedCompletion acceptVerifiedDeferred(
            Object owner,
            IpcReceiptCorrelation.Envelope envelope,
            long generation,
            IpcReceiptCorrelation.Stage stage) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge verified transition");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(stage, "stage");
        CompletableFuture<IpcReceiptCorrelation.Stage> done = null;
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            correlations.acceptVerified(envelope, stage);
            if (stage != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                done = completions.remove(envelope.messageSequence());
                if (done == null) {
                    throw new IllegalStateException("missing host completion");
                }
            }
        }
        return done == null ? null : new DeferredVerifiedCompletion(done, stage);
    }

    void acceptVerified(Object owner,
                        IpcReceiptCorrelation.Envelope envelope,
                        long generation,
                        IpcReceiptCorrelation.Stage stage) {
        DeferredVerifiedCompletion completion =
                acceptVerifiedDeferred(owner, envelope, generation, stage);
        if (completion != null) completion.publish();
    }

    /**
     * Trusted LOCAL send-path rejection, prior to dispatch commitment.
     * Do not route authenticated remote receipts or actor guest DATA here.
     * User callbacks complete only after releasing the correlation monitor.
     */
    void rejectBeforeRemote(IpcReceiptCorrelation.Envelope envelope) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge local rejection");
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<IpcReceiptCorrelation.Stage> done;
        synchronized (gate) {
            requireUnboundPhysicalBridge();
            correlations.rejectBeforeRemote(envelope);
            done = completions.remove(envelope.messageSequence());
            if (done == null) {
                throw new IllegalStateException("missing host completion");
            }
        }
        done.complete(IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
    }

    void rejectBeforeRemote(Object owner,
                            IpcReceiptCorrelation.Envelope envelope,
                            long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge local rejection");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<IpcReceiptCorrelation.Stage> done;
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            correlations.rejectBeforeRemote(envelope);
            done = completions.remove(envelope.messageSequence());
            if (done == null) {
                throw new IllegalStateException("missing host completion");
            }
        }
        done.complete(IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
    }

    /**
     * Only the local trusted adapter may report physical session loss.
     * Use the complete authenticated transport epoch; never a reusable
     * string session id or a peer-provided EOF claim.
     */
    int transportLost(IpcReceiptCorrelation.Envelope trustedEpoch) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge transport loss");
        synchronized (gate) {
            if (physicalOwner != null) {
                throw new IllegalStateException(
                        "bound IPC bridge requires physical owner/generation");
            }
        }
        return transportLostInternal(trustedEpoch);
    }

    int transportLost(Object owner,
                      IpcReceiptCorrelation.Envelope trustedEpoch,
                      long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge transport loss");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        synchronized (gate) {
            requirePhysicalTransport(owner, trustedEpoch, generation);
        }
        return transportLostInternal(trustedEpoch);
    }

    private int transportLostInternal(IpcReceiptCorrelation.Envelope trustedEpoch) {
        ArrayList<CompletableFuture<IpcReceiptCorrelation.Stage>> done = new ArrayList<>();
        synchronized (gate) {
            // Reserve all notification capacity BEFORE the native-equivalent
            // receipt table starts its no-throw retirement phase.
            done.ensureCapacity(completions.size());
            if (completions.size() != correlations.counters().pending()) {
                throw new IllegalStateException("host correlation parity violated");
            }
            var losses = correlations.retireTransportLost(trustedEpoch);
            for (var loss : losses) {
                var completion = completions.remove(loss.envelope().messageSequence());
                if (completion == null) throw new IllegalStateException("missing lost completion");
                done.add(completion);
            }
        }
        for (var future : done) {
            future.complete(IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        }
        return done.size();
    }

    public IpcReceiptCorrelation.Counters counters() {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge counters");
        synchronized (gate) { return correlations.counters(); }
    }

    public boolean invariant() {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt bridge invariant");
        synchronized (gate) {
            return correlations.invariant()
                    && completions.size() == correlations.counters().pending();
        }
    }
}
