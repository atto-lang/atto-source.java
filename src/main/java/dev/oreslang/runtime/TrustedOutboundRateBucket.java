package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Trusted-host token-bucket building block for a future UNTRUSTED actor
 * outbound supervisor admission gate.
 *
 * <p>NOT wired to ActorRuntime.send or to the Graal isolation boundary.
 * This primitive alone provides NO security enforcement. Its clock, rate
 * policy, and instance must remain inaccessible to guest code. The future
 * admission gate must also perform authenticated sender lookup, hierarchical
 * atomic reservation, byte size verification, bounded outstanding credits,
 * enqueue rollback and separate protected lifecycle messages.</p>
 *
 * <p>Uses integer nanosecond arithmetic and deliberately bounded parameters
 * to prevent overflow. Token-bucket semantics permit configured bursts;
 * they are not an exact rolling-one-second ceiling.</p>
 */
final class TrustedOutboundRateBucket {
    private static final long SECOND_NS = 1_000_000_000L;
    private static final long MAX_TOKENS_OR_RATE = 1_000_000_000L;

    private final long rate;
    private final long burst;
    private final LongSupplier monotonicNanos;
    private long available;
    private long lastNanos;
    private long remainder;

    TrustedOutboundRateBucket(long rate, long burst, LongSupplier monotonicNanos) {
        if (rate <= 0 || rate > MAX_TOKENS_OR_RATE
                || burst <= 0 || burst > MAX_TOKENS_OR_RATE) {
            throw new IllegalArgumentException("invalid bounded token bucket policy");
        }
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "trusted clock");
        long sample = monotonicNanos.getAsLong();
        if (sample < 0) {
            throw new IllegalStateException("trusted clock returned negative time");
        }
        this.rate = rate;
        this.burst = burst;
        this.available = burst;
        this.lastNanos = sample;
    }

    /**
     * Nonparking admission. In a real runtime, only the authenticated host
     * routing layer may call this method. A false result never enqueues.
     */
    synchronized boolean tryConsume(long units) {
        if (units <= 0 || units > burst) {
            throw new IllegalArgumentException("invalid token charge");
        }
        refill();
        if (available < units) {
            return false;
        }
        available -= units;
        return true;
    }

    /**
     * INTERNAL ONLY: roll back a provisional charge rejected before its lease
     * was ever published to a caller. A published admission consumes start
     * rate even when cancelled before IO, or delayed refunds can inflate
     * a later token-bucket burst. Outstanding credits settle separately.
     */
    synchronized void rollbackUnpublished(long units) {
        if (units <= 0 || units > burst) {
            throw new IllegalArgumentException("invalid refund");
        }
        refill();
        available = Math.min(burst, available + units);
        if (available == burst) {
            remainder = 0;
        }
    }

    synchronized long remaining() {
        refill();
        return available;
    }

    private void refill() {
        long now = monotonicNanos.getAsLong();
        if (now < 0) {
            throw new IllegalStateException("trusted clock returned negative time");
        }
        if (now <= lastNanos) {
            // A rolled-back sample cannot refill or reset lastNanos.
            return;
        }
        long elapsed = now - lastNanos;
        lastNanos = now;
        if (available == burst) {
            remainder = 0;
            return;
        }
        long missing = burst - available;
        // missing <= 10^9, so all products and sums below fit signed i64.
        long numeratorToFull = missing * SECOND_NS - remainder;
        long nsToFull = numeratorToFull / rate
                + ((numeratorToFull % rate) == 0 ? 0 : 1);
        if (elapsed >= nsToFull) {
            available = burst;
            remainder = 0;
            return;
        }
        long numerator = elapsed * rate + remainder;
        available += numerator / SECOND_NS;
        remainder = numerator % SECOND_NS;
    }
}
