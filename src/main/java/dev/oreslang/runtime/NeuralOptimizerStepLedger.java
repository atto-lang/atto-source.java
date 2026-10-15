package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Host-side in-process barrier for one model optimizer step.
 *
 * This is NOT a durable transaction log or optimizer implementation. Only a
 * trusted supervisor that has already verified gradient provenance should
 * call markGradient; model/optimizer/checkpoint commits require persistent
 * journaling and native tensor mutation not yet wired into source actors.
 */
public final class NeuralOptimizerStepLedger {
    private final NeuralActorSchema.Ticket root;
    private final int microbatches;
    private final Set<Long> completed = new HashSet<>();
    private boolean aborted;
    private boolean committed;

    public NeuralOptimizerStepLedger(
            NeuralActorSchema.Ticket root, int expectedMicrobatches) {
        this.root = Objects.requireNonNull(root, "model training ticket");
        if (!root.training()) {
            throw new IllegalArgumentException("optimizer requires a training-step ticket");
        }
        if (expectedMicrobatches < 1 || expectedMicrobatches > 4096) {
            throw new IllegalArgumentException("optimizer step requires 1..4096 microbatches");
        }
        this.microbatches = expectedMicrobatches;
    }

    public record Snapshot(
            long step, int completedMicrobatches,
            int expectedMicrobatches, boolean aborted, boolean committed) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(root.trainingStep(), completed.size(),
                microbatches, aborted, committed);
    }

    /**
     * Note that the ticket alone is not a cryptographic proof of a computed
     * gradient. An authenticated/autodiff-verified native caller must perform
     * that check before entering this barrier.
     */
    public synchronized void markGradient(NeuralActorSchema.Ticket ticket) {
        Objects.requireNonNull(ticket, "gradient ticket");
        requireActive();
        if (!root.model().equals(ticket.model())
                || !root.requestId().equals(ticket.requestId())
                || root.batchId() != ticket.batchId()
                || root.trainingStep() != ticket.trainingStep()
                || ticket.microbatchId() >= microbatches) {
            throw new SecurityException("optimizer gradient ticket does not match step and microbatch");
        }
        if (!completed.add(ticket.microbatchId())) {
            throw new IllegalStateException("duplicate gradient microbatch");
        }
    }

    public synchronized boolean readyToCommit() {
        return !aborted && !committed && completed.size() == microbatches;
    }

    /**
     * In-process once-only transition. This does not apply tensor updates;
     * durable recovery must commit weights+slots+RNG+step atomically.
     */
    public synchronized void commit() {
        requireActive();
        if (completed.size() != microbatches) {
            throw new IllegalStateException("optimizer step has missing gradient microbatches");
        }
        committed = true;
    }

    public synchronized void abort() {
        if (committed) throw new IllegalStateException("committed optimizer step cannot be aborted");
        aborted = true;
    }

    private void requireActive() {
        if (aborted || committed) {
            throw new IllegalStateException("optimizer step is no longer active");
        }
    }
}
