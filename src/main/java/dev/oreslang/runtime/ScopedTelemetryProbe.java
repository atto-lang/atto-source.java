package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal runtime-owned span lifecycle primitive.
 *
 * One scope is owned by a logical execution + actor generation, NOT a Java
 * thread/carrier. A suspended continuation retains the same scope. Terminal
 * recording is at-most-once even when cancel/STOP/return race. This class
 * neither schedules tasks nor exports telemetry, so no guest callback or I/O
 * can occur while an actor holds a critical state loan.
 *
 * Package-private: guest and foreign host adapters cannot call this by name.
 * A future compiler bridge must authorize execution/tenant/generation identity,
 * not trust numeric IDs supplied by untrusted guest code.
 *
 * NOT wired to the source-language "using telemetry.span" lowering yet.
 * Activation, aggregation, sampling and exporter authority are separate.
 */
final class ScopedTelemetryProbe {
    private static final int RUNNING = 0;
    private static final int SUSPENDED = 1;
    private static final int FINISHED = 2;
    private static final ScopedTelemetryProbe DISABLED =
            new ScopedTelemetryProbe(0, 0, 0, 0, true);

    public enum Outcome { OK, ERROR, CANCELLED, ACTOR_STOPPED }

    public record Completion(
            long executionId, long actorGeneration, int probeId,
            long elapsedNs, Outcome outcome) { }

    private final long executionId;
    private final long actorGeneration;
    private final int probeId;
    private final long startedNs;
    private final boolean disabled;
    private final AtomicInteger lifecycle = new AtomicInteger(RUNNING);

    private ScopedTelemetryProbe(
            long executionId, long actorGeneration, int probeId,
            long startedNs, boolean disabled) {
        this.executionId = executionId;
        this.actorGeneration = actorGeneration;
        this.probeId = probeId;
        this.startedNs = startedNs;
        this.disabled = disabled;
    }

    /** Disabled fast path: constant singleton, no clock read or allocation. */
    public static ScopedTelemetryProbe enter(
            boolean enabled, long executionId, long actorGeneration, int probeId) {
        if (!enabled) return DISABLED;
        if (executionId <= 0 || actorGeneration <= 0 || probeId <= 0) {
            throw new IllegalArgumentException("telemetry scope identity must be positive");
        }
        return new ScopedTelemetryProbe(
                executionId, actorGeneration, probeId, System.nanoTime(), false);
    }

    public boolean isRecording() {
        return !disabled && lifecycle.get() != FINISHED;
    }

    private boolean owns(long execution, long generation) {
        return !disabled && executionId == execution && actorGeneration == generation;
    }

    /** Compiler/source continuation calls this once per *logical* suspension. */
    public boolean suspend(long execution, long generation) {
        return owns(execution, generation)
                && lifecycle.compareAndSet(RUNNING, SUSPENDED);
    }

    /** Restore the SAME scope after carrier migration; no new span is born. */
    public boolean resume(long execution, long generation) {
        return owns(execution, generation)
                && lifecycle.compareAndSet(SUSPENDED, RUNNING);
    }

    /**
     * End with an immutable receipt. Only the winning terminal transition
     * allocates a Completion or reads the clock. Caller may offer the receipt
     * to a bounded, nonblocking collector after releasing all runtime locks.
     *
     * Returns null for disabled, foreign, stale or already finished scopes.
     */
    public Completion finish(long execution, long generation, Outcome outcome) {
        if (!owns(execution, generation) || outcome == null) return null;
        while (true) {
            int previous = lifecycle.get();
            if (previous == FINISHED) return null;
            if (lifecycle.compareAndSet(previous, FINISHED)) {
                long elapsed = System.nanoTime() - startedNs;
                // System.nanoTime() differences are only meaningful on the same
                // JVM; a negative value (including wrap) is clamped.
                if (elapsed < 0) elapsed = 0;
                return new Completion(executionId, actorGeneration, probeId,
                        elapsed, outcome);
            }
        }
    }
}
