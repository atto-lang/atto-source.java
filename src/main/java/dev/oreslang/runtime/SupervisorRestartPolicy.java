package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable OTP-style restart intensity/backoff policy.
 *
 * <p>This is a supervisor policy, not an actor failure loop. A value of
 * maxRestarts == 0 means every failure escalates immediately.</p>
 */
public record SupervisorRestartPolicy(
        int maxRestarts,
        Duration restartWindow,
        Duration minBackoff,
        Duration maxBackoff) {

    public SupervisorRestartPolicy {
        if (maxRestarts < 0) {
            throw new IllegalArgumentException("maxRestarts must be >= 0");
        }
        Objects.requireNonNull(restartWindow, "restartWindow");
        Objects.requireNonNull(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        long window = toNanos(restartWindow, "restartWindow");
        long min = toNanos(minBackoff, "minBackoff");
        long max = toNanos(maxBackoff, "maxBackoff");
        if (window <= 0) {
            throw new IllegalArgumentException("restartWindow must be positive");
        }
        if (min < 0 || max < 0) {
            throw new IllegalArgumentException("restart backoff cannot be negative");
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "maxBackoff must be >= minBackoff");
        }
    }

    long restartWindowNanos() {
        return restartWindow.toNanos();
    }

    long minBackoffNanos() {
        return minBackoff.toNanos();
    }

    long maxBackoffNanos() {
        return maxBackoff.toNanos();
    }

    private static long toNanos(Duration value, String name) {
        try {
            return value.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    name + " is too large for monotonic nanosecond accounting",
                    overflow);
        }
    }
}
