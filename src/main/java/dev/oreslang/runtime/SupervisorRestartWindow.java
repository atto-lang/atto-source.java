package dev.oreslang.runtime;

/**
 * Bounded monotonic restart-intensity gate for supervisor policy.
 *
 * <p>This type deliberately owns no threads, timers or actor references. The
 * caller supplies a monotonic timestamp at the instant a restart is actually
 * admitted. At most {@code maxRestarts} admissions may occur in one rolling
 * {@code windowNanos} interval. Denied attempts do not consume capacity.</p>
 *
 * <p>The gate state is a fixed timestamp ring plus primitive counters.
 * {@link #tryAcquireFast(long)} is allocation-free after construction.
 * {@link #tryAcquire(long)} intentionally allocates one immutable Decision for
 * callers that need a self-contained diagnostic result.</p>
 */
public final class SupervisorRestartWindow {
    public record Decision(
            boolean allowed,
            int restartsInWindow,
            long retryAfterNanos,
            long decisionSequence) {
        public Decision {
            if (restartsInWindow < 0) {
                throw new IllegalArgumentException("restartsInWindow must be non-negative");
            }
            if (retryAfterNanos < 0) {
                throw new IllegalArgumentException("retryAfterNanos must be non-negative");
            }
            if (decisionSequence <= 0) {
                throw new IllegalArgumentException("decisionSequence must be positive");
            }
            if (allowed && retryAfterNanos != 0) {
                throw new IllegalArgumentException("allowed restart cannot have retry delay");
            }
        }
    }

    private final int maxRestarts;
    private final long windowNanos;
    private final long diagnosticKey;
    private final long[] admittedAt;

    private int head;
    private int size;
    private boolean clockInitialized;
    private long lastRawNanos;
    private long elapsedNanos;
    private long decisionSequence;
    private long suppressed;
    private long lastRetryAfterNanos;

    public SupervisorRestartWindow(int maxRestarts, long windowNanos) {
        this(maxRestarts, windowNanos, 0L);
    }

    /**
     * @param diagnosticKey stable numeric supervisor identity for optional
     *                      CoreLog correlation; zero means "not supplied".
     */
    public SupervisorRestartWindow(
            int maxRestarts,
            long windowNanos,
            long diagnosticKey) {
        if (maxRestarts < 1 || maxRestarts > 4096) {
            throw new IllegalArgumentException("maxRestarts must be 1..4096");
        }
        if (windowNanos < 1) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        this.maxRestarts = maxRestarts;
        this.windowNanos = windowNanos;
        this.diagnosticKey = diagnosticKey;
        this.admittedAt = new long[maxRestarts];
    }

    /**
     * Attempt to consume one restart admission at the supplied monotonic time.
     *
     * <p>Clock rollback is rejected rather than allowing a caller to evade the
     * rolling budget or manufacture a negative retry interval.</p>
     */
    public synchronized Decision tryAcquire(long nowNanos) {
        boolean allowed = apply(nowNanos);
        return new Decision(
                allowed,
                size,
                lastRetryAfterNanos,
                decisionSequence);
    }

    /**
     * Allocation-free admission API for the supervisor hot path. Detailed
     * retry/sequence state remains available through primitive accessors.
     */
    public synchronized boolean tryAcquireFast(long nowNanos) {
        return apply(nowNanos);
    }

    public synchronized long lastRetryAfterNanos() {
        return lastRetryAfterNanos;
    }

    public synchronized long decisionSequence() {
        return decisionSequence;
    }

    private boolean apply(long nowNanos) {
        // Reject exhausted decisions before changing clock or ring state.
        // Otherwise a failed admission could still expire timestamps or
        // advance the observed clock, violating fail-atomicity.
        if (decisionSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("restart decision sequence exhausted");
        }
        long now = observeMonotonic(nowNanos);
        expire(now);
        nextSequence();

        if (size >= maxRestarts) {
            long oldest = admittedAt[head];
            long deadline = saturatingAdd(oldest, windowNanos);
            lastRetryAfterNanos =
                    deadline <= now ? 0L : deadline - now;
            suppressed = saturatingIncrement(suppressed);
            if (isPowerOfTwo(suppressed)) {
                CoreLog.record(
                        CoreLog.Level.WARN,
                        CoreLog.SUPERVISOR_RESTART_SUPPRESSED,
                        diagnosticKey);
            }
            return false;
        }

        int tail = (head + size) % admittedAt.length;
        admittedAt[tail] = now;
        size++;
        lastRetryAfterNanos = 0L;
        return true;
    }

    /** Read-only counters; no timestamp sampling or eviction occurs here. */
    public synchronized int currentAdmissions() {
        return size;
    }

    public synchronized long suppressedCount() {
        return suppressed;
    }

    public int maxRestarts() {
        return maxRestarts;
    }

    public long windowNanos() {
        return windowNanos;
    }

    /**
     * Explicitly advance the rolling window without consuming a restart.
     * Useful for deterministic supervisor tests and externally driven clocks.
     */
    public synchronized int advance(long nowNanos) {
        long now = observeMonotonic(nowNanos);
        expire(now);
        return size;
    }

    /**
     * Saturating exponential backoff. {@code failureOrdinal=0} yields
     * {@code baseNanos}; larger ordinals double until {@code maxNanos}.
     */
    public static long exponentialBackoffNanos(
            int failureOrdinal,
            long baseNanos,
            long maxNanos) {
        if (failureOrdinal < 0) {
            throw new IllegalArgumentException("failureOrdinal must be non-negative");
        }
        if (baseNanos < 0 || maxNanos < 0 || baseNanos > maxNanos) {
            throw new IllegalArgumentException("invalid backoff bounds");
        }
        if (baseNanos == 0 || baseNanos == maxNanos) {
            return baseNanos;
        }

        long value = baseNanos;
        int remaining = failureOrdinal;
        while (remaining-- > 0 && value < maxNanos) {
            if (value > maxNanos / 2) return maxNanos;
            value *= 2;
        }
        return Math.min(value, maxNanos);
    }

    /**
     * Reproducible pseudo-jitter in {@code [0,maxJitterNanos]}. This avoids
     * restart herds without introducing hidden randomness into incident replay.
     */
    public static long deterministicJitterNanos(
            long stableKey,
            int failureOrdinal,
            long maxJitterNanos) {
        if (failureOrdinal < 0) {
            throw new IllegalArgumentException("failureOrdinal must be non-negative");
        }
        if (maxJitterNanos < 0) {
            throw new IllegalArgumentException("maxJitterNanos must be non-negative");
        }
        if (maxJitterNanos == 0) return 0L;

        long mixed = stableKey
                ^ (0x9E3779B97F4A7C15L * ((long) failureOrdinal + 1L));
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;

        long positive = mixed & Long.MAX_VALUE;
        if (maxJitterNanos == Long.MAX_VALUE) return positive;
        return positive % (maxJitterNanos + 1L);
    }

    /** Combined deterministic delay with saturating addition. */
    public static long restartDelayNanos(
            long stableKey,
            int failureOrdinal,
            long baseNanos,
            long maxBackoffNanos,
            long maxJitterNanos) {
        long backoff = exponentialBackoffNanos(
                failureOrdinal, baseNanos, maxBackoffNanos);
        long jitter = deterministicJitterNanos(
                stableKey, failureOrdinal, maxJitterNanos);
        return saturatingAdd(backoff, jitter);
    }

    private long observeMonotonic(long rawNowNanos) {
        if (!clockInitialized) {
            clockInitialized = true;
            lastRawNanos = rawNowNanos;
            return 0L;
        }
        // System.nanoTime() has an arbitrary signed origin and can wrap.
        // Subtraction is the supported comparison for intervals < 2^63 ns.
        long delta = rawNowNanos - lastRawNanos;
        if (delta < 0) {
            throw new IllegalArgumentException("monotonic clock moved backwards");
        }
        final long nextElapsed;
        try {
            nextElapsed = Math.addExact(elapsedNanos, delta);
        } catch (ArithmeticException exhausted) {
            // A rejected clock sample must not commit its raw origin.
            throw new IllegalStateException("restart elapsed-time sequence exhausted", exhausted);
        }
        lastRawNanos = rawNowNanos;
        elapsedNanos = nextElapsed;
        return nextElapsed;
    }

    private void expire(long nowNanos) {
        while (size > 0) {
            long oldest = admittedAt[head];
            if (nowNanos - oldest < windowNanos) return;
            admittedAt[head] = 0L;
            head = (head + 1) % admittedAt.length;
            size--;
        }
    }

    private long nextSequence() {
        if (decisionSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("restart decision sequence exhausted");
        }
        return ++decisionSequence;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1L)) == 0L;
    }

    static long saturatingAdd(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("saturatingAdd requires non-negative values");
        }
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }
}
