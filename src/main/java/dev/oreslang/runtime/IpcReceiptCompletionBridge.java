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
final class IpcReceiptCompletionBridge {
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
    // Once local physical loss is claimed, this bridge can never publish new
    // DATA. A retry may finish receipt retirement after an exceptional failure,
    // but reconnect always requires a fresh bridge/owner.
    private boolean transportLossClaimed;

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
            if (transportLossClaimed) {
                throw new IllegalStateException(
                        "IPC physical transport already lost");
            }
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
     * Apply one authenticated receipt while the caller holds its ordered
     * receive gate, but do not run an arbitrary completion callback there.
     * The caller completes the returned terminal future after releasing that
     * gate. REMOTE_ACCEPTED returns null because it is not terminal.
     */
    CompletableFuture<IpcReceiptCorrelation.Stage> acceptVerifiedDeferred(
            Object owner, IpcReceiptCorrelation.Envelope envelope,
            long generation, IpcReceiptCorrelation.Stage stage) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge verified transition");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(stage, "stage");
        CompletableFuture<IpcReceiptCorrelation.Stage> done = null;
        synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            if (transportLossClaimed) {
                throw new IllegalStateException(
                        "IPC physical transport already lost");
            }
            if (stage != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                done = completions.get(envelope.messageSequence());
                if (done == null) {
                    throw new IllegalStateException("missing host completion");
                }
            }
            correlations.acceptVerified(envelope, stage);
            if (done != null) {
                CompletableFuture<IpcReceiptCorrelation.Stage> removed =
                        completions.remove(envelope.messageSequence());
                if (removed != done) {
                    throw new IllegalStateException(
                            "host completion identity changed under receipt gate");
                }
            }
        }
        return done;
    }

    /** Host bridge use outside a transport receive gate. */
    void acceptVerified(Object owner,
                        IpcReceiptCorrelation.Envelope envelope,
                        long generation,
                        IpcReceiptCorrelation.Stage stage) {
        CompletableFuture<IpcReceiptCorrelation.Stage> done =
                acceptVerifiedDeferred(owner, envelope, generation, stage);
        if (done != null) done.complete(stage);
    }

    /**
     * Retired correlation outcomes prepared without invoking callbacks. Only
     * the trusted host may call complete() and it must do so after releasing
     * transport lifecycle locks and publishing physical RETIRED status.
     */
    static final class RetiredCompletions {
        private final ArrayList<CompletableFuture<IpcReceiptCorrelation.Stage>> done =
                new ArrayList<>();
        private List<IpcReceiptCorrelation.LostCorrelation> losses = List.of();

        int size() { return done.size(); }

        void complete() {
            for (int i = 0; i < losses.size(); ++i) {
                var loss = losses.get(i);
                IpcReceiptCorrelation.Stage terminal =
                        loss.lastObserved() == IpcReceiptCorrelation.Stage.LOCAL_ADMITTED
                                ? IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE
                                : IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN;
                done.get(i).complete(terminal);
            }
        }
    }

    /**
     * Fence admission and retire the receipt ledger, but do not execute user
     * completion callbacks. Physical shutdown uses this to publish RETIRED
     * before any dependent application callback can block its cleanup worker.
     * Every potentially allocating container is prepared before ledger
     * mutation, so allocation failure leaves retry authority intact.
     */
    RetiredCompletions transportLostDeferred(
            Object owner, IpcReceiptCorrelation.Envelope trustedEpoch,
            long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge transport loss");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        RetiredCompletions terminal = new RetiredCompletions();
        synchronized (gate) {
            requirePhysicalTransport(owner, trustedEpoch, generation);
            transportLossClaimed = true;
            terminal.done.ensureCapacity(completions.size());
            if (completions.size() != correlations.counters().pending()) {
                throw new IllegalStateException("host correlation parity violated");
            }
            terminal.losses = correlations.retireTransportLost(trustedEpoch);
            for (var loss : terminal.losses) {
                var completion = completions.remove(
                        loss.envelope().messageSequence());
                if (completion == null) {
                    throw new IllegalStateException("missing lost completion");
                }
                terminal.done.add(completion);
            }
        }
        return terminal;
    }

    /** Compatibility entry point for non-lease host self-tests. */
    int transportLost(Object owner,
                      IpcReceiptCorrelation.Envelope trustedEpoch,
                      long generation) {
        RetiredCompletions terminal = transportLostDeferred(
                owner, trustedEpoch, generation);
        terminal.complete();
        return terminal.size();
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
