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
    public static final int LOOP_CHECK_INTERVAL = 128;
    public static final int MAX_BACKEDGES_PER_DISPATCH = 512;
    public static final long SOFT_DISPATCH_QUANTUM_NANOS = 2_000_000L;

    private final LongSupplier monotonicNanos;
    private long lastCooperateAtNanos;
    private long dispatchStartedAtNanos;
    private long backedgesThisDispatch;
    // Persistent across await/resume: short turns cannot evade cold checks.
    private int pollPhase;
    private boolean pollDue;
    // A requested handoff belongs to the logical task, not one carrier turn.
    private boolean pendingHandoff;
    // Prevent a completed 512-edge turn from relatching after real cooperation.
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
        // Await/carrier migration is not a cooperation. Keep overdue work
        // pending until the logical task actually hands off.
        if (!acknowledgedCooperate
                && backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) {
            pendingHandoff = true;
        }
        acknowledgedCooperate = false;
        dispatchStartedAtNanos = monotonicNanos.getAsLong();
        backedgesThisDispatch = 0;
        // A new physical dispatch must earn a fresh poll ticket.
        pollDue = false;
    }

    /**
     * Called at the compiler-lowered, committed loop backedge.
     * Absolutely no clock reads or scheduler calls on the usual fast path.
     */
    public boolean completedBackedgeNeedsCheck() {
        // Charge cheap work each iteration, even when an overdue critical
        // section prohibits suspension. Saturate the physical dispatch count.
        if (backedgesThisDispatch != Long.MAX_VALUE) {
            backedgesThisDispatch++;
        }
        // The cold poll remains periodic even past the 512 reduction cap.
        pollPhase = (pollPhase + 1) & (LOOP_CHECK_INTERVAL - 1);
        pollDue = pollPhase == 0;
        return pollDue;
    }

    /** Slow path only: reduction cap, watchdog request, or active dispatch time.
     * The watchdog cannot force suspension: the caller must already own a
     * post-commit, heap-resumable continuation and be outside no-yield scopes.
     */
    public boolean shouldCooperateAtCheck() {
        return shouldCooperateAtCheck(false);
    }

    public boolean shouldCooperateAtCheck(boolean watchdogRequested) {
        // One ticket per 128 committed backedges: repeated or off-cycle
        // callers must not trigger new clock reads or scheduler polls.
        if (!pollDue) {
            return false;
        }
        pollDue = false;
        if (pendingHandoff || watchdogRequested
                || backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) {
            pendingHandoff = true;
            return true;
        }
        if (elapsedNanosOrOverdue(
                monotonicNanos.getAsLong(), dispatchStartedAtNanos)
                >= SOFT_DISPATCH_QUANTUM_NANOS) {
            pendingHandoff = true;
            return true;
        }
        return false;
    }

    /** Called only for an actual rt cooperate / automatic scheduler handoff. */
    public void recordedCooperate() {
        lastCooperateAtNanos = monotonicNanos.getAsLong();
        pollDue = false;
        pendingHandoff = false;
        acknowledgedCooperate = true;
    }

    /**
     * Clock-free indication for a compiler-verified no-yield-scope exit.
     * This is a request only; it never authorizes suspension by itself.
     */
    public boolean pendingHandoffOnNoYieldExit() {
        return pendingHandoff
                || (!acknowledgedCooperate
                    && backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH);
    }

    private static long elapsedNanosOrOverdue(long now, long started) {
        // A regressing clock or signed subtraction overflow must fail toward
        // fairness, never make an overdue task appear to have a fresh quantum.
        if (now < started) return Long.MAX_VALUE;
        long elapsed = now - started;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    /**
     * Scheduling metric, not generator yield count/time.
     * Its sole call-site should be deliberate diagnostics or a slow path.
     */
    public long timeSinceLastCooperateNanos() {
        return elapsedNanosOrOverdue(
                monotonicNanos.getAsLong(), lastCooperateAtNanos);
    }

    public long completedBackedgesThisDispatch() {
        return backedgesThisDispatch;
    }
}
