package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * Trusted host adapter between one bounded receipt table and terminal host
 * completion stages. NOT a network authenticator or an Oreslang guest Future.
 *
 * Never runs arbitrary CompletableFuture completion callbacks with its gate
 * held. Source actor scheduling must separately project results onto CONTROL.
 */
final class IpcReceiptCompletionBridge {
    // Per-transport capacity alone is insufficient: a malicious peer could
    // open many independent transports and pin a virtual thread per callback.
    // Reserve one global slot BEFORE receipt admission, and hold it until all
    // synchronous dependents for that terminal have returned. Fail closed at
    // admission on exhaustion, never while trying to retire an admitted receipt.
    private static final Semaphore globalCompletionBudget =
            new Semaphore(16_384);

    private final Object gate = new Object();
    private final IpcReceiptCorrelation correlations;
    private final int capacity;
    private final Map<Long, CompletableFuture<IpcReceiptCorrelation.Stage>> completions =
            new HashMap<>();
    // A completion remains charged against this bridge until synchronous
    // dependent callbacks return on its isolated delivery virtual thread.
    // Together with completions.size(), this keeps delivery work bounded by
    // the same configured receipt capacity and prevents an unbounded callback
    // backlog while still allowing independent terminal notifications.
    private int deliveriesInFlight;
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
        this.capacity = capacity;
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
        if (!globalCompletionBudget.tryAcquire()) {
            throw new IllegalStateException(
                    "bounded global IPC completion capacity exhausted");
        }
        boolean accepted = false;
        try {
            CompletableFuture<IpcReceiptCorrelation.Stage> future =
                    new CompletableFuture<>();
            CompletionStage<IpcReceiptCorrelation.Stage> publicView =
                    future.minimalCompletionStage();
            synchronized (gate) {
            requirePhysicalTransport(owner, envelope, generation);
            if (transportLossClaimed) {
                throw new IllegalStateException(
                        "IPC physical transport already lost");
            }
            if (completions.size() + deliveriesInFlight >= capacity) {
                throw new IllegalStateException(
                        "bounded IPC completion delivery capacity exhausted");
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
            accepted = true;
            return publicView;
        } finally {
            if (!accepted) globalCompletionBudget.release();
        }
    }

    /**
     * One prestarted, isolated terminal-delivery carrier. It is created before
     * any irreversible ledger transition, then armed under the bridge gate and
     * released only after the caller has dropped its receive/lifecycle locks.
     */
    final class PreparedDelivery {
        private final CompletableFuture<IpcReceiptCorrelation.Stage> done;
        private final CountDownLatch release = new CountDownLatch(1);
        private IpcReceiptCorrelation.Stage terminal;
        private boolean armed;

        private PreparedDelivery(
                CompletableFuture<IpcReceiptCorrelation.Stage> done) {
            this.done = Objects.requireNonNull(done, "done");
            Thread worker = Thread.ofVirtual()
                    .name("ipc-receipt-completion")
                    .unstarted(this::run);
            // Thread creation/start is deliberately preflighted before receipt
            // mutation. A resource failure therefore leaves ledger authority
            // unchanged rather than losing an already-known terminal result.
            worker.start();
        }

        private void arm(IpcReceiptCorrelation.Stage stage) {
            if (armed) {
                throw new IllegalStateException("IPC completion delivery already armed");
            }
            terminal = Objects.requireNonNull(stage, "stage");
            armed = true;
        }

        void deliver() {
            RuntimeBlockingGuard.requireBlockingHostThread(
                    "IPC terminal completion delivery");
            release.countDown();
        }

        private void cancel() {
            release.countDown();
        }

        private void run() {
            boolean interrupted = false;
            for (;;) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (!armed) return;
            try {
                done.complete(terminal);
            } finally {
                synchronized (gate) {
                    if (deliveriesInFlight <= 0) {
                        throw new IllegalStateException(
                                "IPC completion delivery accounting underflow");
                    }
                    --deliveriesInFlight;
                }
                globalCompletionBudget.release();
            }
        }

        private boolean owns(
                CompletableFuture<IpcReceiptCorrelation.Stage> candidate) {
            return done == candidate;
        }
    }

    /**
     * Apply one authenticated receipt while the caller holds its ordered
     * receive gate, but do not run an arbitrary completion callback there.
     * A terminal transition prestarts an isolated delivery carrier before
     * mutating the ledger; the caller releases it after dropping the receive
     * gate. REMOTE_ACCEPTED returns null because it is not terminal.
     */
    PreparedDelivery acceptVerifiedDeferred(
            Object owner, IpcReceiptCorrelation.Envelope envelope,
            long generation, IpcReceiptCorrelation.Stage stage) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge verified transition");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(stage, "stage");
        PreparedDelivery delivery = null;
        boolean armed = false;
        try {
            synchronized (gate) {
                requirePhysicalTransport(owner, envelope, generation);
                if (transportLossClaimed) {
                    throw new IllegalStateException(
                            "IPC physical transport already lost");
                }
                CompletableFuture<IpcReceiptCorrelation.Stage> done = null;
                if (stage != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                    done = completions.get(envelope.messageSequence());
                    if (done == null) {
                        throw new IllegalStateException("missing host completion");
                    }
                    delivery = new PreparedDelivery(done);
                }
                correlations.acceptVerified(envelope, stage);
                if (done != null) {
                    CompletableFuture<IpcReceiptCorrelation.Stage> removed =
                            completions.remove(envelope.messageSequence());
                    if (removed != done) {
                        throw new IllegalStateException(
                                "host completion identity changed under receipt gate");
                    }
                    ++deliveriesInFlight;
                    delivery.arm(stage);
                    armed = true;
                }
            }
            return delivery;
        } catch (RuntimeException | Error failure) {
            if (delivery != null && !armed) delivery.cancel();
            throw failure;
        }
    }

    /** Host bridge use outside a transport receive gate. */
    void acceptVerified(Object owner,
                        IpcReceiptCorrelation.Envelope envelope,
                        long generation,
                        IpcReceiptCorrelation.Stage stage) {
        PreparedDelivery delivery =
                acceptVerifiedDeferred(owner, envelope, generation, stage);
        if (delivery != null) delivery.deliver();
    }

    /**
     * Retired correlation outcomes whose delivery carriers were reserved before
     * ledger mutation. complete() only releases those already-prepared virtual
     * threads; it never invokes dependent callbacks on the cleanup worker.
     */
    final class RetiredCompletions {
        private final ArrayList<PreparedDelivery> deliveries = new ArrayList<>();

        int size() { return deliveries.size(); }

        void complete() {
            RuntimeBlockingGuard.requireBlockingHostThread(
                    "IPC retired completion delivery");
            for (PreparedDelivery delivery : deliveries) delivery.deliver();
        }

        private void cancelPrepared() {
            for (PreparedDelivery delivery : deliveries) delivery.cancel();
        }
    }

    /**
     * Fence admission and retire the receipt ledger without executing user
     * completion callbacks. Exact loss/completion identity and isolated
     * delivery carriers are preflighted before the irreversible ledger
     * transition. Physical shutdown can therefore publish RETIRED first, then
     * release independent terminal deliveries without serial callback head-of-
     * line blocking on its cleanup worker.
     */
    RetiredCompletions transportLostDeferred(
            Object owner, IpcReceiptCorrelation.Envelope trustedEpoch,
            long generation) {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC receipt bridge transport loss");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        RetiredCompletions terminal = new RetiredCompletions();
        boolean committed = false;
        try {
            synchronized (gate) {
                requirePhysicalTransport(owner, trustedEpoch, generation);
                if (completions.size() != correlations.counters().pending()) {
                    throw new IllegalStateException("host correlation parity violated");
                }

                List<IpcReceiptCorrelation.LostCorrelation> preview =
                        correlations.previewTransportLost(trustedEpoch);
                if (preview.size() != completions.size()) {
                    throw new IllegalStateException(
                            "host transport-loss completion parity violated");
                }

                // Reserve all list storage before starting any delivery carrier.
                // From this point until receipt retirement, the preflight loop
                // performs no container growth: if resource reservation fails,
                // no parked worker has been published and the ledger is intact.
                terminal.deliveries.ensureCapacity(preview.size());
                for (var loss : preview) {
                    long sequence = loss.envelope().messageSequence();
                    var completion = completions.get(sequence);
                    if (completion == null) {
                        throw new IllegalStateException(
                                "missing preflight lost completion");
                    }
                    PreparedDelivery delivery = new PreparedDelivery(completion);
                    terminal.deliveries.add(delivery);
                }

                List<IpcReceiptCorrelation.LostCorrelation> losses =
                        correlations.retireTransportLost(trustedEpoch);
                // No bridge caller can interleave while gate is held. Claim
                // physical loss only after retirement has completed, so a
                // pre-mutation allocation/start failure remains retryable.
                transportLossClaimed = true;
                if (!losses.equals(preview)) {
                    throw new IllegalStateException(
                            "IPC transport-loss preview changed under bridge gate");
                }

                for (int i = 0; i < losses.size(); ++i) {
                    var loss = losses.get(i);
                    long sequence = loss.envelope().messageSequence();
                    var completion = completions.remove(sequence);
                    PreparedDelivery delivery = terminal.deliveries.get(i);
                    if (completion == null || !delivery.owns(completion)) {
                        throw new IllegalStateException(
                                "lost completion identity changed under receipt gate");
                    }
                    IpcReceiptCorrelation.Stage terminalStage =
                            loss.lastObserved()
                                    == IpcReceiptCorrelation.Stage.LOCAL_ADMITTED
                                    ? IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE
                                    : IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN;
                    ++deliveriesInFlight;
                    delivery.arm(terminalStage);
                }
                if (!completions.isEmpty()) {
                    throw new IllegalStateException(
                            "host transport-loss completion residue");
                }
                committed = true;
            }
            return terminal;
        } catch (RuntimeException | Error failure) {
            if (!committed) terminal.cancelPrepared();
            throw failure;
        }
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
            return deliveriesInFlight >= 0
                    && deliveriesInFlight <= capacity
                    && completions.size() + deliveriesInFlight <= capacity
                    && correlations.invariant()
                    && completions.size() == correlations.counters().pending();
        }
    }
}
