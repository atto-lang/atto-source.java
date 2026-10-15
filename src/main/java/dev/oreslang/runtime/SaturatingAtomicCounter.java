package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Saturating loss/diagnostic counters for host CONTROL paths.
 *
 * <p>Never wrap a non-negative audit counter to a negative value. The CAS
 * preserves concurrent increments without allocating per-event callbacks.
 * Diagnostic counter saturation does not change notification admission.
 */
final class SaturatingAtomicCounter {
    private SaturatingAtomicCounter() {}

    static long increment(AtomicLong counter) {
        return add(counter, 1L);
    }

    static long add(AtomicLong counter, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("counter delta must not be negative");
        }
        for (;;) {
            long current = counter.get();
            // An old overflowed counter also fails closed to saturation.
            long next = current < 0 || current > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : current + amount;
            if (next == current || counter.compareAndSet(current, next)) {
                return next;
            }
        }
    }
}
