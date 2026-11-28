package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Actor-facing coordinator for one durable development-loop run.
 *
 * <p>This class contains no network, Git, model, CI or UI side effects. It is
 * the narrow orchestration boundary a concrete Oreslang actor can own: typed
 * input events are reduced deterministically, persisted through a
 * {@link WorkflowJournalStoreV1}, and external activity completions are bound
 * atomically to their causal workflow events.</p>
 */
public final class DurableDevLoopCoordinatorV1 implements AutoCloseable {

    private final WorkflowJournalStoreV1 store;
    private final String runId;
    private final int maxIterations;
    private boolean closed;

    public DurableDevLoopCoordinatorV1(
            WorkflowJournalStoreV1 store,
            String runId,
            int maxIterations) {
        this.store = Objects.requireNonNull(store, "store");
        this.runId = requireText(runId, "runId", 512);
        if (maxIterations <= 0 || maxIterations > 10_000) {
            throw new IllegalArgumentException(
                    "maxIterations must be in [1, 10000]");
        }
        this.maxIterations = maxIterations;
        verifyStoreIdentity();
    }

    public synchronized DurableDevLoopV1.Transition accept(
            String eventId,
            DurableDevLoopV1.Event event) {
        ensureOpen();
        Objects.requireNonNull(event, "event");

        DurableDevLoopV1 machine = currentMachine();
        DurableDevLoopV1.Transition transition =
                machine.accept(eventId, event);
        store.append(transition);
        return transition;
    }

    /**
     * Atomically commit a successful external effect receipt together with the
     * workflow event that the effect produced.
     */
    public synchronized DurableDevLoopV1.Transition completeEffect(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis,
            String eventId,
            DurableDevLoopV1.Event event) {
        ensureOpen();
        Objects.requireNonNull(event, "event");
        verifyStoreIdentity();
        return store.completeAndAppend(
                effectKey,
                claimToken,
                receiptRef,
                nowMillis,
                eventId,
                event);
    }

    public synchronized Optional<WorkflowJournalV1.Claim> claimNextEffect(
            String workerId,
            long nowMillis,
            long leaseMillis) {
        ensureOpen();
        verifyStoreIdentity();
        return store.claimNext(workerId, nowMillis, leaseMillis);
    }

    public synchronized void releaseUnexecutedEffect(
            String effectKey,
            String claimToken,
            String reasonRef,
            long nowMillis) {
        ensureOpen();
        verifyStoreIdentity();
        store.releaseUnexecuted(
                effectKey,
                claimToken,
                reasonRef,
                nowMillis);
    }

    public synchronized int expireEffectClaims(long nowMillis) {
        ensureOpen();
        verifyStoreIdentity();
        return store.expireClaims(nowMillis);
    }

    public synchronized void reconcileEffectAsCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef) {
        ensureOpen();
        verifyStoreIdentity();
        store.resolveAmbiguousAsCompleted(
                effectKey,
                receiptRef,
                reconciliationRef);
    }

    public synchronized void reconcileEffectForRetry(
            String effectKey,
            String reconciliationRef) {
        ensureOpen();
        verifyStoreIdentity();
        store.resolveAmbiguousForRetry(
                effectKey,
                reconciliationRef);
    }

    public synchronized WorkflowRunProjectionV1 projection() {
        ensureOpen();
        WorkflowJournalStoreV1.ReadView view = store.readView();
        verifyStoreIdentity(view.storeSnapshot());
        return WorkflowRunProjectionV1.from(
                view.replay().machine().snapshot(),
                view.storeSnapshot());
    }

    public synchronized DurableDevLoopV1.Snapshot workflowSnapshot() {
        ensureOpen();
        WorkflowJournalStoreV1.ReadView view = store.readView();
        verifyStoreIdentity(view.storeSnapshot());
        return view.replay().machine().snapshot();
    }

    public synchronized WorkflowJournalV1.StoreSnapshot storeSnapshot() {
        ensureOpen();
        verifyStoreIdentity();
        return store.snapshot();
    }

    public synchronized String runId() {
        return runId;
    }

    public synchronized int maxIterations() {
        return maxIterations;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        store.close();
    }

    private DurableDevLoopV1 currentMachine() {
        WorkflowJournalV1.StoreSnapshot snapshot = store.snapshot();
        if (snapshot.runId() == null) {
            return new DurableDevLoopV1(runId, maxIterations);
        }
        WorkflowJournalStoreV1.ReadView view = store.readView();
        verifyStoreIdentity(view.storeSnapshot());
        return view.replay().machine();
    }

    private void verifyStoreIdentity() {
        verifyStoreIdentity(store.snapshot());
    }

    private void verifyStoreIdentity(
            WorkflowJournalV1.StoreSnapshot snapshot) {
        if (snapshot.runId() == null) {
            if (snapshot.maxIterations() != null
                    || snapshot.journalEntries() != 0) {
                throw new IllegalStateException(
                        "empty workflow store has bound run state");
            }
            return;
        }

        if (!runId.equals(snapshot.runId())) {
            throw new IllegalStateException(
                    "workflow coordinator run id does not match store");
        }
        if (snapshot.maxIterations() == null
                || snapshot.maxIterations() != maxIterations) {
            throw new IllegalStateException(
                    "workflow coordinator policy does not match store");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "durable dev-loop coordinator is closed");
        }
    }

    private static String requireText(
            String value,
            String name,
            int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length " + maxLength);
        }
        return normalized;
    }
}
