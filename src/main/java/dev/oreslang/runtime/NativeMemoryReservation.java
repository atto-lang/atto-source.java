package dev.oreslang.runtime;

/**
 * Internal retryable physical-memory accounting token.
 *
 * Implementations must be idempotent after successful close and must retain
 * retry authority when aggregate accounting release fails.
 */
interface NativeMemoryReservation extends AutoCloseable {
    @Override void close();
}
