package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Deterministic bounded restart-intensity circuit for one supervisor.
 *
 * <p>This class decides restart <em>admission</em> and enforces the ordering
 * proof around it; it does not create actors, sleep, schedule timers or invoke
 * guest code. The caller must first observe a generation-fenced terminal
 * CONTROL notice, ask this circuit for a plan, quiesce selected old generations
 * in reverse declaration order, wait the returned delay without blocking a
 * carrier, then publish replacements in declaration order.</p>
 *
 * <p>Restart budget is charged per replacement child, not merely per failure
 * episode. Restart history uses a fixed primitive ring buffer, so restart storms
 * do not allocate boxed timestamps on the hot failure path.</p>
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
            int pendingReplacements,
            int quiescedPendingReplacements,
            long acceptedReplacementRestarts,
            long escalations,
            long overlappingEpisodeEscalations,
            long staleNotices,
            long staleQuiescenceAcks,
            long noRestartDecisions,
            long clockRegressions,
            long nextDecisionSequence) { }

    private final Config config;
    private final long[] generation;
    private final long[] startedAtNanos;
    private final int[] consecutiveAttempts;
    private final boolean[] live;

    /**
     * Selected generations are logically fenced immediately. The failed child
     * is already physically quiesced when its terminal CONTROL notice arrives;
     * selected siblings must be acknowledged explicitly before replacement.
     */
    private final boolean[] restartPending;
    private final boolean[] restartQuiesced;
    private final long[] restartEpisode;

    /** Fixed-capacity primitive ring: no Long boxing during a restart storm. */
    private final long[] restartTimes;
    private int restartHead;
    private int restartSize;

    private boolean hasNowNanos;
    private long lastNowNanos;
    private long nextDecisionSequence = 1L;
    private long acceptedReplacementRestarts;
    private long escalations;
    private long overlappingEpisodeEscalations;
    private long staleNotices;
    private long staleQuiescenceAcks;
    private long noRestartDecisions;
    private long clockRegressions;

    ActorSupervisorRestartCircuit(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.generation = new long[config.childCount()];
        this.startedAtNanos = new long[config.childCount()];
        this.consecutiveAttempts = new int[config.childCount()];
        this.live = new boolean[config.childCount()];
        this.restartPending = new boolean[config.childCount()];
        this.restartQuiesced = new boolean[config.childCount()];
        this.restartEpisode = new long[config.childCount()];
        this.restartTimes = new long[config.maxRestarts()];
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

        if (restartPending[childIndex]) {
            if (!restartQuiesced[childIndex]) {
                throw new IllegalStateException(
                        "replacement cannot start before old generation physically quiesces");
            }
            long episode = restartEpisode[childIndex];
            for (int i = 0; i < childIndex; ++i) {
                if (restartPending[i] && restartEpisode[i] == episode) {
                    throw new IllegalStateException(
                            "replacement generations must start in declaration order");
                }
            }
        }

        generation[childIndex] = actorGeneration;
        startedAtNanos[childIndex] = nowNanos;
        live[childIndex] = true;
        clearRestartState(childIndex);
    }

    /**
     * Record physical finalization of one selected old sibling. A duplicate or
     * stale generation returns false; an out-of-order quiescence proof is a
     * runtime orchestration bug and fails closed.
     */
    synchronized boolean acknowledgeQuiesced(
            int childIndex,
            long actorGeneration,
            long nowNanos) {
        requireChild(childIndex);
        requireMonotonicTime(nowNanos);

        if (!restartPending[childIndex]
                || generation[childIndex] != actorGeneration
                || restartQuiesced[childIndex]) {
            staleQuiescenceAcks = saturatingIncrement(staleQuiescenceAcks);
            return false;
        }

        long episode = restartEpisode[childIndex];
        for (int i = childIndex + 1; i < config.childCount(); ++i) {
            if (restartPending[i]
                    && restartEpisode[i] == episode
                    && !restartQuiesced[i]) {
                throw new IllegalStateException(
                        "selected old generations must quiesce in reverse declaration order");
            }
        }

        restartQuiesced[childIndex] = true;
        return true;
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
            staleNotices = saturatingIncrement(staleNotices);
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

        // Terminal CONTROL is published only after physical finalization, so
        // the failed generation itself is already quiesced at this boundary.
        live[childIndex] = false;

        if (!shouldRestart(policy, exitClass)) {
            noRestartDecisions = saturatingIncrement(noRestartDecisions);
            consecutiveAttempts[childIndex] = 0;
            clearRestartState(childIndex);
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

        // Never overwrite an already admitted restart episode. This can occur
        // when REST_FOR_ONE has younger replacements pending and an older live
        // child fails before that episode completes. Coalescing would require a
        // new budget/order proof; fail closed by escalating instead.
        for (int i = first; i < end; ++i) {
            if (restartPending[i]) {
                escalations = saturatingIncrement(escalations);
                overlappingEpisodeEscalations =
                        saturatingIncrement(overlappingEpisodeEscalations);
                return new RestartPlan(
                        DecisionKind.ESCALATE,
                        first,
                        end,
                        0,
                        0,
                        0,
                        decisionSequence,
                        "overlapping-restart-episode");
            }
        }

        pruneWindow(nowNanos);
        if (width > config.maxRestarts()
                || restartSize > config.maxRestarts() - width) {
            escalations = saturatingIncrement(escalations);
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

        // Fence the complete episode before returning the plan. The failed
        // child is already quiesced; siblings are not.
        for (int i = first; i < end; ++i) {
            live[i] = false;
            restartPending[i] = true;
            restartQuiesced[i] = i == childIndex;
            restartEpisode[i] = decisionSequence;
        }

        for (int i = 0; i < width; ++i) addRestartTime(nowNanos);
        acceptedReplacementRestarts =
                saturatingAdd(acceptedReplacementRestarts, width);

        return new RestartPlan(
                DecisionKind.RESTART,
                first,
                end,
                attempt,
                width,
                restartDelayNanos(childIndex, actorGeneration, attempt),
                decisionSequence,
                "restart-admitted");
    }

    synchronized boolean restartPending(int childIndex) {
        requireChild(childIndex);
        return restartPending[childIndex];
    }

    synchronized boolean restartQuiesced(int childIndex) {
        requireChild(childIndex);
        return restartQuiesced[childIndex];
    }

    synchronized long generation(int childIndex) {
        requireChild(childIndex);
        return generation[childIndex];
    }

    synchronized Snapshot snapshot(long nowNanos) {
        requireMonotonicTime(nowNanos);
        pruneWindow(nowNanos);
        int pending = 0;
        int quiesced = 0;
        for (int i = 0; i < config.childCount(); ++i) {
            if (restartPending[i]) {
                pending++;
                if (restartQuiesced[i]) quiesced++;
            }
        }
        return new Snapshot(
                restartSize,
                pending,
                quiesced,
                acceptedReplacementRestarts,
                escalations,
                overlappingEpisodeEscalations,
                staleNotices,
                staleQuiescenceAcks,
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

    private long restartDelayNanos(
            int childIndex,
            long actorGeneration,
            int attempt) {
        if (config.baseBackoffNanos() == 0 || attempt <= 0) return 0;
        long delay = config.baseBackoffNanos();
        for (int i = 1; i < attempt; ++i) {
            if (delay >= config.maxBackoffNanos()) {
                return config.maxBackoffNanos();
            }
            if (delay > config.maxBackoffNanos() / 2) {
                delay = config.maxBackoffNanos();
                break;
            }
            delay *= 2;
        }
        delay = Math.min(delay, config.maxBackoffNanos());

        // Deterministic <=12.5% phase spread avoids synchronized supervisor
        // herds without sacrificing reproducibility or requiring entropy.
        long remaining = config.maxBackoffNanos() - delay;
        long spreadCap = Math.min(delay >>> 3, remaining);
        if (spreadCap <= 0) return delay;

        long mixed = mix64(
                actorGeneration
                        ^ (((long) childIndex) << 32)
                        ^ ((long) attempt * 0x9E3779B97F4A7C15L));
        long spread = Long.remainderUnsigned(mixed, spreadCap + 1L);
        return delay + spread;
    }

    private static long mix64(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private void addRestartTime(long nowNanos) {
        if (restartTimes.length == 0 || restartSize >= restartTimes.length) {
            throw new IllegalStateException("restart intensity ring overflow");
        }
        int tail = (restartHead + restartSize) % restartTimes.length;
        restartTimes[tail] = nowNanos;
        restartSize++;
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
        while (restartSize != 0 && restartTimes[restartHead] < cutoff) {
            restartHead = (restartHead + 1) % restartTimes.length;
            restartSize--;
        }
        if (restartSize == 0) restartHead = 0;
    }

    private void requireMonotonicTime(long nowNanos) {
        // nanoTime has an arbitrary signed origin. A separate presence bit
        // avoids reserving Long.MIN_VALUE as an impossible timestamp.
        if (hasNowNanos && nowNanos - lastNowNanos < 0) {
            clockRegressions = saturatingIncrement(clockRegressions);
            throw new IllegalArgumentException("supervisor monotonic clock regressed");
        }
        hasNowNanos = true;
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

    private void clearRestartState(int childIndex) {
        restartPending[childIndex] = false;
        restartQuiesced[childIndex] = false;
        restartEpisode[childIndex] = 0L;
    }

    private static int incrementAttempt(int previous) {
        if (previous == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return previous + 1;
    }

    private static long nonNegativeElapsed(long now, long started) {
        long elapsed = now - started;
        if (elapsed < 0) {
            throw new IllegalStateException("child start time is in the future");
        }
        return elapsed;
    }

    private void requireChild(int childIndex) {
        if (childIndex < 0 || childIndex >= config.childCount()) {
            throw new IndexOutOfBoundsException("child index out of range");
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatingAdd(long value, long delta) {
        if (delta <= 0) return value;
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
