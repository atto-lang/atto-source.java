package dev.oreslang.runtime;

/** Pure retry admission arithmetic; the scheduler owns clocks, timers and settlement. */
final class BoundedRetryPolicyV1 {
    enum Backoff { FIXED, EXPONENTIAL }
    enum Decision { WAIT, CANCELLED, DEADLINE, INELIGIBLE, EXHAUSTED }
    record Outcome(Decision decision, long delayNanos) {}
    private final int attempts;
    private final long initial, cap, deadline;
    private final Backoff backoff;

    BoundedRetryPolicyV1(int attempts, long initial, long cap, long deadline, Backoff backoff) {
        if (attempts < 1 || initial < 0 || cap < initial || deadline <= 0 || backoff == null)
            throw new IllegalArgumentException("invalid retry policy");
        this.attempts = attempts;
        this.initial = initial;
        this.cap = cap;
        this.deadline = deadline;
        this.backoff = backoff;
    }

    // Elapsed time must be a nonnegative monotonic duration from the first
    // attempt. Caller classifies only ordinary eligible failures as eligible.
    Outcome afterFailure(int completed, long elapsed, boolean eligible, boolean cancelled) {
        if (completed < 1 || completed > attempts || elapsed < 0)
            throw new IllegalArgumentException("invalid attempt observation");
        if (cancelled) return stop(Decision.CANCELLED);
        if (elapsed >= deadline) return stop(Decision.DEADLINE);
        if (!eligible) return stop(Decision.INELIGIBLE);
        if (completed == attempts) return stop(Decision.EXHAUSTED);
        long delay = initial;
        if (backoff == Backoff.EXPONENTIAL && delay != 0) {
            // At most 63 doublings regardless of the configured attempt count.
            for (int i = 1; i < completed && delay < cap; i++)
                delay = delay > cap / 2 ? cap : delay * 2;
        }
        // A wake exactly at the deadline cannot admit a new attempt.
        if (delay >= deadline - elapsed) return stop(Decision.DEADLINE);
        return new Outcome(Decision.WAIT, delay);
    }

    private static Outcome stop(Decision decision) { return new Outcome(decision, 0); }
}
