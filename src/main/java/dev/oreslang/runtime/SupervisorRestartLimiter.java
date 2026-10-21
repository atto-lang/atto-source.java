package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Stateful, bounded supervisor restart-intensity accounting.
 *
 * <p>The limiter never restarts an actor itself. Supervisors first inspect the
 * retained generation-bound CONTROL event, ask this limiter for a decision,
 * then either acquire a restart permit after physical DONE or escalate and
 * acknowledge the retained event. This keeps policy above the low-level
 * restart capability and prevents hidden crash loops.</p>
 */
public final class SupervisorRestartLimiter {

    private static final int DEFAULT_MAX_TRACKED_ACTORS = 4_096;

    public enum Action { RESTART, ESCALATE }

    /**
     * Replayable jitter source. The returned value is a signed nanosecond
     * offset applied to deterministic exponential backoff and clamped to
     * [0,maxBackoff].
     */
    @FunctionalInterface
    public interface Jitter {
        long offsetNanos(
                ActorRuntime.ActorId actorId,
                long generation,
                int restartOrdinal,
                long deterministicBackoffNanos);

        static Jitter none() {
            return (actorId, generation, ordinal, backoff) -> 0L;
        }
    }

    public record Decision(
            Action action,
            Duration backoff,
            int restartsInWindow,
            long generation,
            ActorFailure.Kind failureKind,
            boolean repeatedObservation) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(backoff, "backoff");
            Objects.requireNonNull(failureKind, "failureKind");
            if (restartsInWindow < 0) {
                throw new IllegalArgumentException(
                        "restartsInWindow cannot be negative");
            }
            if (generation <= 0) {
                throw new IllegalArgumentException(
                        "restart decision generation must be positive");
            }
        }
    }

    public record Snapshot(
            int restartsInWindow,
            long lastFailureGeneration,
            Duration currentBackoff,
            boolean escalated) {
        public Snapshot {
            if (restartsInWindow < 0 || lastFailureGeneration < 0) {
                throw new IllegalArgumentException("invalid restart snapshot");
            }
            Objects.requireNonNull(currentBackoff, "currentBackoff");
        }
    }

    private static final class History {
        final ArrayDeque<Long> restartTimes = new ArrayDeque<>();
        long stableGeneration;
        long lastFailureGeneration;
        Decision lastDecision;
        long currentBackoffNanos;
        boolean escalated;
    }

    private final SupervisorRestartPolicy policy;
    private final LongSupplier monotonicClock;
    private final Jitter jitter;
    private final int maxTrackedActors;
    private final Map<ActorRuntime.ActorId, History> histories =
            new HashMap<>();
    private boolean clockObserved;
    private long lastClockNanos;

    public SupervisorRestartLimiter(SupervisorRestartPolicy policy) {
        this(
                policy,
                System::nanoTime,
                Jitter.none(),
                DEFAULT_MAX_TRACKED_ACTORS);
    }

    /**
     * Injectable clock+jitter constructor for deterministic replay/testing.
     * The clock must never move backwards for the lifetime of this limiter.
     */
    public SupervisorRestartLimiter(
            SupervisorRestartPolicy policy,
            LongSupplier monotonicClock,
            Jitter jitter) {
        this(
                policy,
                monotonicClock,
                jitter,
                DEFAULT_MAX_TRACKED_ACTORS);
    }

    public SupervisorRestartLimiter(
            SupervisorRestartPolicy policy,
            LongSupplier monotonicClock,
            Jitter jitter,
            int maxTrackedActors) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.monotonicClock =
                Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        if (maxTrackedActors <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedActors must be positive");
        }
        this.maxTrackedActors = maxTrackedActors;
    }

    public SupervisorRestartPolicy policy() {
        return policy;
    }

    /**
     * Record or replay the policy decision for one exact terminal generation.
     * Re-observing the same retained CONTROL event is idempotent and does not
     * consume another restart slot.
     */
    /**
     * Low-level value-based decision surface retained for deterministic policy
     * tests and embedders that own their own authoritative event source.
     * ActorRuntime itself uses decideAuthoritative(), which is package-private
     * and reached only after retained CONTROL lookup succeeds.
     */
    public synchronized Decision decide(
            ActorRuntime.SupervisorControlEvent event) {
        return decideInternal(event);
    }

    synchronized Decision decideAuthoritative(
            ActorRuntime.SupervisorControlEvent event) {
        return decideInternal(event);
    }

    private Decision decideInternal(
            ActorRuntime.SupervisorControlEvent event) {
        Objects.requireNonNull(event, "event");
        long now = monotonicNow();
        History history = histories.get(event.actorId());
        if (history == null) {
            if (histories.size() >= maxTrackedActors) {
                return new Decision(
                        Action.ESCALATE,
                        Duration.ZERO,
                        0,
                        event.generation(),
                        event.failureKind(),
                        false);
            }
            history = new History();
            histories.put(event.actorId(), history);
        }

        if (event.generation() < history.stableGeneration
                || event.generation() < history.lastFailureGeneration) {
            throw new IllegalArgumentException(
                    "stale supervisor failure generation "
                            + event.generation() + " for " + event.actorId());
        }
        if (event.generation() == history.lastFailureGeneration
                && history.lastDecision != null) {
            Decision previous = history.lastDecision;
            return new Decision(
                    previous.action(),
                    previous.backoff(),
                    previous.restartsInWindow(),
                    previous.generation(),
                    previous.failureKind(),
                    true);
        }

        prune(history, now);
        history.lastFailureGeneration = event.generation();

        if (history.escalated
                || history.restartTimes.size() >= policy.maxRestarts()) {
            history.escalated = true;
            history.currentBackoffNanos = 0L;
            Decision decision = new Decision(
                    Action.ESCALATE,
                    Duration.ZERO,
                    history.restartTimes.size(),
                    event.generation(),
                    event.failureKind(),
                    false);
            history.lastDecision = decision;
            return decision;
        }

        int ordinal = history.restartTimes.size() + 1;
        long deterministic = deterministicBackoffNanos(ordinal);
        long adjusted = applyJitter(
                event.actorId(),
                event.generation(),
                ordinal,
                deterministic);
        history.restartTimes.addLast(now);
        history.currentBackoffNanos = adjusted;

        Decision decision = new Decision(
                Action.RESTART,
                Duration.ofNanos(adjusted),
                history.restartTimes.size(),
                event.generation(),
                event.failureKind(),
                false);
        history.lastDecision = decision;
        return decision;
    }

    /**
     * Clear crash-loop occupancy after a supervisor-defined stability point.
     * A stale generation cannot reset a newer actor's restart budget.
     */
    public synchronized void resetAfterStability(
            ActorRuntime.ActorId actorId,
            long generation) {
        resetAfterStabilityInternal(actorId, generation);
    }

    synchronized void resetAfterAuthoritativeStability(
            ActorRuntime.ActorId actorId,
            long generation) {
        resetAfterStabilityInternal(actorId, generation);
    }

    private void resetAfterStabilityInternal(
            ActorRuntime.ActorId actorId,
            long generation) {
        Objects.requireNonNull(actorId, "actorId");
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "stable generation must be positive");
        }
        History history = histories.get(actorId);
        if (history == null) {
            if (histories.size() >= maxTrackedActors) {
                throw new IllegalStateException(
                        "supervisor restart history capacity exhausted");
            }
            history = new History();
            histories.put(actorId, history);
        }
        if ((history.lastFailureGeneration > 0
                    && generation <= history.lastFailureGeneration)
                || generation < history.stableGeneration) {
            throw new IllegalArgumentException(
                    "stability reset requires a strictly newer generation");
        }
        history.restartTimes.clear();
        history.stableGeneration = generation;
        history.currentBackoffNanos = 0L;
        history.escalated = false;
        if (history.lastFailureGeneration < generation) {
            history.lastDecision = null;
        }
    }

    /**
     * Forget one quiescent logical actor after its final stable retirement.
     * The caller must supply a generation not older than any failure/stability
     * generation retained by this limiter; stale cleanup cannot erase a newer
     * crash-loop budget.
     */
    public synchronized boolean forget(
            ActorRuntime.ActorId actorId,
            long generation) {
        Objects.requireNonNull(actorId, "actorId");
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "forgotten generation must be positive");
        }
        History history = histories.get(actorId);
        if (history == null) return false;
        if (generation < history.lastFailureGeneration
                || generation < history.stableGeneration) {
            throw new IllegalArgumentException(
                    "stale generation cannot forget restart history");
        }
        return histories.remove(actorId, history);
    }

    public synchronized int trackedActors() {
        return histories.size();
    }

    public int maxTrackedActors() {
        return maxTrackedActors;
    }

    public synchronized Snapshot snapshot(ActorRuntime.ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId");
        long now = monotonicNow();
        History history = histories.get(actorId);
        if (history == null) {
            return new Snapshot(0, 0L, Duration.ZERO, false);
        }
        prune(history, now);
        return new Snapshot(
                history.restartTimes.size(),
                history.lastFailureGeneration,
                Duration.ofNanos(history.currentBackoffNanos),
                history.escalated);
    }

    private long monotonicNow() {
        long now = monotonicClock.getAsLong();
        if (clockObserved && now < lastClockNanos) {
            throw new IllegalStateException(
                    "supervisor restart monotonic clock moved backwards");
        }
        clockObserved = true;
        lastClockNanos = now;
        return now;
    }

    private void prune(History history, long now) {
        long window = policy.restartWindowNanos();
        while (!history.restartTimes.isEmpty()) {
            long oldest = history.restartTimes.peekFirst();
            long elapsed = now - oldest;
            if (elapsed < 0 || elapsed < window) break;
            history.restartTimes.removeFirst();
        }
        if (history.restartTimes.isEmpty() && !history.escalated) {
            history.currentBackoffNanos = 0L;
        }
    }

    private long deterministicBackoffNanos(int ordinal) {
        long max = policy.maxBackoffNanos();
        long value = policy.minBackoffNanos();
        if (value == 0L || ordinal <= 1) return Math.min(value, max);
        for (int i = 1; i < ordinal && value < max; i++) {
            if (value > max / 2L) return max;
            value *= 2L;
        }
        return Math.min(value, max);
    }

    private long applyJitter(
            ActorRuntime.ActorId actorId,
            long generation,
            int ordinal,
            long deterministic) {
        long offset = jitter.offsetNanos(
                actorId, generation, ordinal, deterministic);
        long adjusted;
        try {
            adjusted = Math.addExact(deterministic, offset);
        } catch (ArithmeticException overflow) {
            adjusted = offset >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        if (adjusted < 0L) return 0L;
        return Math.min(adjusted, policy.maxBackoffNanos());
    }
}
