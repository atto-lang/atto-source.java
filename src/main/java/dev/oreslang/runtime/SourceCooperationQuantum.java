package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Soft, per-dispatch loop quantum for a single heap-backed Oreslang source task.
 *
 * <p>One cheap counter increment and mask per completed backedge; monotonic
 * clock reads occur only after an amortized batch. This class decides when a
 * checkpoint is due, NOT where suspension is legal. Only SourceTask's proven
 * post-commit continuation can publish OresScheduler.cooperate().</p>
 *
 * <p>The logical task survives carrier migration: the time since its last
 * cooperation is intentionally distinct from the current dispatch's quantum.
 * A long pending await starts a fresh soft quantum on resumption but does not
 * count as a cooperation or run the task's lexical cleanup.</p>
 */
public final class SourceCooperationQuantum {
    public static final int LOOP_CHECK_INTERVAL = 64;
    public static final int MAX_BACKEDGES_PER_DISPATCH = 512;
    public static final long SOFT_DISPATCH_QUANTUM_NANOS = 2_000_000L;

    private final LongSupplier monotonicNanos;
    private long lastCooperateAtNanos;
    private long dispatchStartedAtNanos;
    private int backedgesThisDispatch;

    /** Production clock, injected in tests to validate exact clock-read cost. */
    public SourceCooperationQuantum() {
        this(System::nanoTime);
    }

    public SourceCooperationQuantum(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        lastCooperateAtNanos = monotonicNanos.getAsLong();
        dispatchStartedAtNanos = lastCooperateAtNanos;
    }

    /** Refresh only the fairness quantum; keep the logical cooperation clock. */
    public void beginDispatch() {
        dispatchStartedAtNanos = monotonicNanos.getAsLong();
        backedgesThisDispatch = 0;
    }

    /**
     * Called at the compiler-lowered, committed loop backedge.
     * Absolutely no clock reads or scheduler calls on the usual fast path.
     */
    public boolean completedBackedgeNeedsCheck() {
        int completed = ++backedgesThisDispatch;
        return (completed & (LOOP_CHECK_INTERVAL - 1)) == 0;
    }

    /** Slow path only: reduction cap OR current dispatched active time. */
    public boolean shouldCooperateAtCheck() {
        if (backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) return true;
        return monotonicNanos.getAsLong() - dispatchStartedAtNanos
                >= SOFT_DISPATCH_QUANTUM_NANOS;
    }

    /** Called only for an actual rt cooperate / automatic scheduler handoff. */
    public void recordedCooperate() {
        lastCooperateAtNanos = monotonicNanos.getAsLong();
    }

    /**
     * Scheduling metric, not generator yield count/time.
     * Its sole call-site should be deliberate diagnostics or a slow path.
     */
    public long timeSinceLastCooperateNanos() {
        return Math.max(0L, monotonicNanos.getAsLong() - lastCooperateAtNanos);
    }

    public int completedBackedgesThisDispatch() {
        return backedgesThisDispatch;
    }
}
