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

    public IpcReceiptCompletionBridge(int capacity) {
        correlations = new IpcReceiptCorrelation(capacity);
    }

    public CompletionStage<IpcReceiptCorrelation.Stage> admit(
            IpcReceiptCorrelation.Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        // Prepare all callback bookkeeping before admitting the DATA receipt.
        CompletableFuture<IpcReceiptCorrelation.Stage> future = new CompletableFuture<>();
        CompletionStage<IpcReceiptCorrelation.Stage> publicView = future.minimalCompletionStage();
        synchronized (gate) {
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

    /** Caller MUST verify the remote peer/frame/capability before this call. */
    public void acceptVerified(IpcReceiptCorrelation.Envelope envelope,
                               IpcReceiptCorrelation.Stage stage) {
        CompletableFuture<IpcReceiptCorrelation.Stage> done = null;
        synchronized (gate) {
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
     * Trusted LOCAL send-path rejection, prior to remote publication.
     * Do not route authenticated remote receipts or actor guest DATA here.
     * User callbacks complete only after releasing the correlation monitor.
     */
    void rejectBeforeRemote(IpcReceiptCorrelation.Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<IpcReceiptCorrelation.Stage> done;
        synchronized (gate) {
            correlations.rejectBeforeRemote(envelope);
            done = completions.remove(envelope.messageSequence());
            if (done == null) {
                throw new IllegalStateException("missing host completion");
            }
        }
        done.complete(IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
    }

    public int transportLost(String trustedSession) {
        ArrayList<CompletableFuture<IpcReceiptCorrelation.Stage>> done = new ArrayList<>();
        synchronized (gate) {
            // Reserve all notification capacity BEFORE the native-equivalent
            // receipt table starts its no-throw retirement phase.
            done.ensureCapacity(completions.size());
            if (completions.size() != correlations.counters().pending()) {
                throw new IllegalStateException("host correlation parity violated");
            }
            var losses = correlations.retireTransportLost(trustedSession);
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
        synchronized (gate) { return correlations.counters(); }
    }

    public boolean invariant() {
        synchronized (gate) {
            return correlations.invariant()
                    && completions.size() == correlations.counters().pending();
        }
    }
}
