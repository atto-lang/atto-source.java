package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Soft, per-dispatch loop quantum for a single heap-backed Oreslang source task.
 *
 * <p>One cheap saturating reduction update and phase increment per completed backedge; monotonic
 * clock reads occur only after an amortized 128-backedge batch. This class decides when a
 * checkpoint is due, NOT where suspension is legal. Only SourceTask's proven
 * post-commit continuation can publish OresScheduler.cooperate().</p>
 *
 * <p>The logical task survives carrier migration: the time since its last
 * cooperation is intentionally distinct from the current dispatch's quantum.
 * A long pending await starts a fresh soft quantum on resumption but does not
 * count as a cooperation or run the task's lexical cleanup.</p>
 */
public final class SourceCooperationQuantum {
    public static final int LOOP_CHECK_INTERVAL = 128;
    public static final int MAX_BACKEDGES_PER_DISPATCH = 512;
    public static final long SOFT_DISPATCH_QUANTUM_NANOS = 2_000_000L;

    private final LongSupplier monotonicNanos;
    private long lastCooperateAtNanos;
    private long dispatchStartedAtNanos;
    private long backedgesThisDispatch;
    // Separate wrapping phase: the saturated long must not freeze polling.
    private int pollPhase;
    // Consume at most one cold poll at each valid 128-backedge boundary.
    private boolean pollDue;
    // A deferred 512-backedge or watchdog/time handoff belongs to this task,
    // not its current physical carrier dispatch.
    private boolean pendingHandoff;
    // A genuine cooperation acknowledges the old physical dispatch's debt.
    private boolean acknowledgedCooperate;

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
        // A plain await or carrier change must not erase an overdue handoff.
        // Do not relatch a cap already acknowledged by a genuine cooperate.
        if (!acknowledgedCooperate && backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) {
            pendingHandoff = true;
        }
        acknowledgedCooperate = false;
        dispatchStartedAtNanos = monotonicNanos.getAsLong();
        backedgesThisDispatch = 0;
        // Do not reset pollPhase: repeated 127-edge awaits must not evade checks.
        // Discard stale tickets; a fresh physical dispatch needs a new edge.
        pollDue = false;
    }

    /**
     * Called at the compiler-lowered, committed loop backedge.
     * Absolutely no clock reads or scheduler calls on the usual fast path.
     */
    public boolean completedBackedgeNeedsCheck() {
        // Every committed backedge pays only for saturating reduction
        // accounting and a tiny counter. The expensive safepoint is eligible
        // only on every 128th backedge, including after the soft 512 cap.
        // A no-yield region may delay handoff by up to 127 more reductions,
        // never turn the expensive poll into a per-iteration operation.
        // Unlike masking the saturated long, an independent phase keeps
        // polling even when total reductions reach Long.MAX_VALUE.
        if (backedgesThisDispatch != Long.MAX_VALUE) {
            backedgesThisDispatch++;
        }
        pollPhase++;
        if (pollPhase == LOOP_CHECK_INTERVAL) {
            pollPhase = 0;
            pollDue = true;
            return true;
        }
        pollDue = false;
        return false;
    }

    /** Slow path only: reduction cap, watchdog request, or active dispatch time.
     * The watchdog cannot force suspension: the caller must already own a
     * post-commit, heap-resumable continuation and be outside no-yield scopes.
     */
    public boolean shouldCooperateAtCheck() {
        return shouldCooperateAtCheck(false);
    }

    public boolean shouldCooperateAtCheck(boolean watchdogRequested) {
        // A direct/off-cycle call must never sample the clock or act on the watchdog.
        // This method consumes exactly one ticket from completedBackedgeNeedsCheck().
        if (!pollDue) return false;
        pollDue = false;
        // A pending watchdog notice is advisory and must not cause another
        // clock read. Only the compiler-lowered caller may publish Cooperate.
        if (pendingHandoff || watchdogRequested
                || backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) {
            pendingHandoff = true;
            return true;
        }
        if (elapsedNanosOrOverdue(monotonicNanos.getAsLong(), dispatchStartedAtNanos)
                >= SOFT_DISPATCH_QUANTUM_NANOS) {
            pendingHandoff = true;
            return true;
        }
        return false;
    }

    /**
     * Read-only, clock-free hint for an independently verified no-yield exit.
     * Returning true does not authorize suspension through live guards,
     * borrows, cleanup or an unresumable host stack.
     */
    public boolean pendingHandoffOnNoYieldExit() {
        return pendingHandoff || backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH;
    }

    /** Called only for an actual rt cooperate / automatic scheduler handoff. */
    public void recordedCooperate() {
        lastCooperateAtNanos = monotonicNanos.getAsLong();
        pollDue = false;
        pendingHandoff = false;
        acknowledgedCooperate = true;
    }

    /**
     * Scheduling metric, not generator yield count/time.
     * Its sole call-site should be deliberate diagnostics or a slow path.
     */
    public long timeSinceLastCooperateNanos() {
        return elapsedNanosOrOverdue(
                monotonicNanos.getAsLong(), lastCooperateAtNanos);
    }

    /**
     * Fail closed on clock regression and signed subtraction overflow.
     * A wrapped positive delta can otherwise make a long-overdue dispatch
     * look younger than the soft quantum. Never do raw signed subtraction
     * of two potentially extreme nanoTime readings.
     */
    private static long elapsedNanosOrOverdue(long now, long started) {
        try {
            long delta = Math.subtractExact(now, started);
            return delta >= 0L ? delta : Long.MAX_VALUE;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public long completedBackedgesThisDispatch() {
        return backedgesThisDispatch;
    }
}
