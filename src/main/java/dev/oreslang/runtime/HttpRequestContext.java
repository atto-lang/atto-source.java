package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable, non-authoritative HTTP request identity and timing metadata.
 *
 * <p>Created once during transport admission and owned by one HTTP exchange
 * state, not a JVM carrier thread or a reusable handler actor. A snapshot is
 * safe for tracing after await or carrier migration, but possession never
 * grants guest entry, exchange ownership, actor-state access or permissions.
 * The raw target is client-supplied data and must never be used as authority.</p>
 */
public record HttpRequestContext(
        String requestId,
        long admittedNanos,
        int deadlineMillis,
        String method,
        String target) {
    public HttpRequestContext {
        if (Objects.requireNonNull(requestId, "requestId").isBlank()) {
            throw new IllegalArgumentException("empty requestId");
        }
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        if (deadlineMillis < 1) {
            throw new IllegalArgumentException("HTTP deadline must be positive");
        }
    }

    /** Remaining monotonic timeout; snapshots cannot extend their deadline. */
    public long remainingNanos(long monotonicNowNanos) {
        long elapsed = monotonicNowNanos - admittedNanos;
        long timeout = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        if (elapsed <= 0) return timeout;
        return elapsed >= timeout ? 0L : timeout - elapsed;
    }
}
