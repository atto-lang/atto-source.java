package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic bounded restart-intensity circuit for one supervisor.
 *
 * <p>This class decides restart <em>admission</em>; it does not create actors,
 * sleep, schedule timers or invoke guest code. The caller must first observe a
 * generation-fenced terminal CONTROL notice, ask this circuit for a plan,
 * quiesce the selected old generations, wait the returned delay without
 * blocking a carrier, then publish replacement generations in declared order.</p>
 *
 * <p>Restart budget is charged per replacement child, not merely per failure
 * episode. Thus one-for-all cannot bypass the intensity limit by restarting a
 * large subtree as one logical event.</p>
 */
final class ActorSupervisorRestartCircuit {
    enum Strategy { ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE }
    enum RestartPolicy { PERMANENT, TRANSIENT, TEMPORARY }
    enum ExitClass { NORMAL, SHUTDOWN, ABNORMAL }
    enum DecisionKind { RESTART, NO_RESTART, STALE, ESCALATE }

    record Config(
            int childCount,
            int maxRestarts,
            long windowNanos,
            long baseBackoffNanos,
            long maxBackoffNanos,
            long stableResetNanos) {
        Config {
            if (childCount < 1 || childCount > 4096) {
                throw new IllegalArgumentException("childCount must be 1..4096");
            }
            if (maxRestarts < 0 || maxRestarts > 4096) {
                throw new IllegalArgumentException("maxRestarts must be 0..4096");
            }
            if (windowNanos <= 0) {
                throw new IllegalArgumentException("windowNanos must be positive");
            }
            if (baseBackoffNanos < 0 || maxBackoffNanos < baseBackoffNanos) {
                throw new IllegalArgumentException("invalid restart backoff bounds");
            }
            if (stableResetNanos < 0) {
                throw new IllegalArgumentException("stableResetNanos cannot be negative");
            }
        }
    }

    record RestartPlan(
            DecisionKind kind,
            int firstChild,
            int endExclusive,
            int attempt,
            int chargedRestarts,
            long delayNanos,
            long decisionSequence,
            String reason) {
        RestartPlan {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
            if (firstChild < 0 || endExclusive < firstChild) {
                throw new IllegalArgumentException("invalid restart span");
            }
            if (attempt < 0 || chargedRestarts < 0 || delayNanos < 0
                    || decisionSequence <= 0) {
                throw new IllegalArgumentException("invalid restart decision metadata");
            }
            if (kind != DecisionKind.RESTART
                    && (attempt != 0 || chargedRestarts != 0 || delayNanos != 0)) {
                throw new IllegalArgumentException(
                        "non-restart decisions cannot carry restart work");
            }
        }

        int width() {
            return endExclusive - firstChild;
        }
    }

    record Snapshot(
            int restartsInWindow,
            long acceptedReplacementRestarts,
            long escalations,
            long staleNotices,
            long noRestartDecisions,
            long clockRegressions,
            long nextDecisionSequence) { }

    private final Config config;
    private final long[] generation;
    private final long[] startedAtNanos;
    private final int[] consecutiveAttempts;
    private final boolean[] live;
    /**
     * Old generations selected by a restart episode. A new generation may be
     * admitted only after the runtime has physically quiesced the old one.
     */
    private final boolean[] restartPending;
    private final ArrayDeque<Long> restartTimes = new ArrayDeque<>();

    private long lastNowNanos = Long.MIN_VALUE;
    private long nextDecisionSequence = 1L;
    private long acceptedReplacementRestarts;
    private long escalations;
    private long staleNotices;
    private long noRestartDecisions;
    private long clockRegressions;

    ActorSupervisorRestartCircuit(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.generation = new long[config.childCount()];
        this.startedAtNanos = new long[config.childCount()];
        this.consecutiveAttempts = new int[config.childCount()];
        this.live = new boolean[config.childCount()];
        this.restartPending = new boolean[config.childCount()];
    }

    synchronized void admitGeneration(
            int childIndex,
            long actorGeneration,
            long nowNanos) {
        requireChild(childIndex);
        requireMonotonicTime(nowNanos);
        if (actorGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        if (live[childIndex]) {
            throw new IllegalStateException("child already has a live generation");
        }
        if (actorGeneration <= generation[childIndex]) {
            throw new IllegalStateException("replacement generation must increase");
        }
        generation[childIndex] = actorGeneration;
        startedAtNanos[childIndex] = nowNanos;
        live[childIndex] = true;
        restartPending[childIndex] = false;
    }

    synchronized RestartPlan onExit(
            int childIndex,
            long actorGeneration,
            RestartPolicy policy,
            ExitClass exitClass,
            Strategy strategy,
            long nowNanos) {
        requireChild(childIndex);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(exitClass, "exitClass");
        Objects.requireNonNull(strategy, "strategy");
        requireMonotonicTime(nowNanos);
        long decisionSequence = nextDecisionSequence();

        if (!live[childIndex] || generation[childIndex] != actorGeneration) {
            staleNotices++;
            return new RestartPlan(
                    DecisionKind.STALE,
                    childIndex,
                    childIndex,
                    0,
                    0,
                    0,
                    decisionSequence,
                    "stale-or-already-planned-generation");
        }

        // The terminal event is now owned by this decision. A duplicate notice
        // for the same generation becomes STALE and cannot consume budget.
        live[childIndex] = false;

        if (!shouldRestart(policy, exitClass)) {
            noRestartDecisions++;
            consecutiveAttempts[childIndex] = 0;
            restartPending[childIndex] = false;
            return new RestartPlan(
                    DecisionKind.NO_RESTART,
                    childIndex,
                    childIndex,
                    0,
                    0,
                    0,
                    decisionSequence,
                    "restart-policy-declined");
        }

        int first = switch (strategy) {
            case ONE_FOR_ONE -> childIndex;
            case ONE_FOR_ALL -> 0;
            case REST_FOR_ONE -> childIndex;
        };
        int end = switch (strategy) {
            case ONE_FOR_ONE -> childIndex + 1;
            case ONE_FOR_ALL, REST_FOR_ONE -> config.childCount();
        };
        int width = end - first;

        pruneWindow(nowNanos);
        if (width > config.maxRestarts()
                || restartTimes.size() > config.maxRestarts() - width) {
            escalations++;
            Arrays.fill(restartPending, first, end, false);
            return new RestartPlan(
                    DecisionKind.ESCALATE,
                    first,
                    end,
                    0,
                    0,
                    0,
                    decisionSequence,
                    "restart-intensity-exhausted");
        }

        long livedFor = nonNegativeElapsed(
                nowNanos, startedAtNanos[childIndex]);
        int attempt;
        if (config.stableResetNanos() > 0
                && livedFor >= config.stableResetNanos()) {
            attempt = 1;
        } else {
            attempt = incrementAttempt(consecutiveAttempts[childIndex]);
        }
        consecutiveAttempts[childIndex] = attempt;

        // Mark every selected old generation logically fenced before returning
        // the plan. Runtime orchestration must still physically stop/quiesce
        // live siblings before admitGeneration() of replacements.
        for (int i = first; i < end; ++i) {
            live[i] = false;
            restartPending[i] = true;
        }

        for (int i = 0; i < width; ++i) restartTimes.addLast(nowNanos);
        acceptedReplacementRestarts += width;

        return new RestartPlan(
                DecisionKind.RESTART,
                first,
                end,
                attempt,
                width,
                restartDelayNanos(attempt),
                decisionSequence,
                "restart-admitted");
    }

    synchronized boolean restartPending(int childIndex) {
        requireChild(childIndex);
        return restartPending[childIndex];
    }

    synchronized long generation(int childIndex) {
        requireChild(childIndex);
        return generation[childIndex];
    }

    synchronized Snapshot snapshot(long nowNanos) {
        requireMonotonicTime(nowNanos);
        pruneWindow(nowNanos);
        return new Snapshot(
                restartTimes.size(),
                acceptedReplacementRestarts,
                escalations,
                staleNotices,
                noRestartDecisions,
                clockRegressions,
                nextDecisionSequence);
    }

    private boolean shouldRestart(RestartPolicy policy, ExitClass exitClass) {
        return switch (policy) {
            case PERMANENT -> true;
            case TRANSIENT -> exitClass == ExitClass.ABNORMAL;
            case TEMPORARY -> false;
        };
    }

    private long restartDelayNanos(int attempt) {
        if (config.baseBackoffNanos() == 0 || attempt <= 0) return 0;
        long delay = config.baseBackoffNanos();
        for (int i = 1; i < attempt; ++i) {
            if (delay >= config.maxBackoffNanos()) return config.maxBackoffNanos();
            if (delay > config.maxBackoffNanos() / 2) return config.maxBackoffNanos();
            delay *= 2;
        }
        return Math.min(delay, config.maxBackoffNanos());
    }

    private void pruneWindow(long nowNanos) {
        long cutoff;
        try {
            cutoff = Math.subtractExact(nowNanos, config.windowNanos());
        } catch (ArithmeticException underflow) {
            cutoff = Long.MIN_VALUE;
        }
        // Half-open window [now-window, now]: a restart exactly at the cutoff
        // still counts, avoiding boundary-dependent intensity oscillation.
        while (!restartTimes.isEmpty() && restartTimes.peekFirst() < cutoff) {
            restartTimes.removeFirst();
        }
    }

    private void requireMonotonicTime(long nowNanos) {
        if (lastNowNanos != Long.MIN_VALUE && nowNanos < lastNowNanos) {
            clockRegressions++;
            throw new IllegalArgumentException("supervisor monotonic clock regressed");
        }
        lastNowNanos = nowNanos;
    }

    private long nextDecisionSequence() {
        long value = nextDecisionSequence;
        if (value <= 0) {
            throw new IllegalStateException("restart decision sequence exhausted");
        }
        nextDecisionSequence = value == Long.MAX_VALUE ? 0 : value + 1;
        return value;
    }

    private static int incrementAttempt(int previous) {
        if (previous == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return previous + 1;
    }

    private static long nonNegativeElapsed(long now, long started) {
        if (now < started) {
            throw new IllegalStateException("child start time is in the future");
        }
        return now - started;
    }

    private void requireChild(int childIndex) {
        if (childIndex < 0 || childIndex >= config.childCount()) {
            throw new IndexOutOfBoundsException("child index out of range");
        }
    }
}
