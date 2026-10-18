package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TrustedOutboundRateBucketTest {
    @Test
    void deterministicBurstAndFractionalRefill() {
        AtomicLong clock = new AtomicLong();
        var bucket = new TrustedOutboundRateBucket(20, 5, clock::get);
        for (int i = 0; i < 5; i++) assertTrue(bucket.tryConsume(1));
        assertFalse(bucket.tryConsume(1));
        clock.set(25_000_000L);
        assertFalse(bucket.tryConsume(1)); // 0.5 tokens
        clock.set(50_000_000L);
        assertTrue(bucket.tryConsume(1));
        assertFalse(bucket.tryConsume(1));
        clock.set(1_050_000_000L);
        assertEquals(5L, bucket.remaining()); // cap, no extra minted
    }

    @Test
    void rollbackAndRejectedChargesDoNotMintTokens() {
        AtomicLong clock = new AtomicLong();
        var bucket = new TrustedOutboundRateBucket(1, 2, clock::get);
        assertTrue(bucket.tryConsume(2));
        assertFalse(bucket.tryConsume(1));
        bucket.refundFailedSend(2);
        assertEquals(2L, bucket.remaining());
        assertTrue(bucket.tryConsume(2));
        assertFalse(bucket.tryConsume(1));
    }

    @Test
    void backwardsClockCannotRefillOrOverflow() {
        AtomicLong clock = new AtomicLong(500);
        var bucket = new TrustedOutboundRateBucket(1_000_000_000, 2, clock::get);
        assertTrue(bucket.tryConsume(2));
        clock.set(499);
        assertEquals(0L, bucket.remaining());
        clock.set(501);
        assertEquals(1L, bucket.remaining());
        clock.set(Long.MAX_VALUE);
        assertEquals(2L, bucket.remaining());
    }

    @Test
    void hostilePolicyAndInvalidChargesFailClosed() {
        AtomicLong clock = new AtomicLong();
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedOutboundRateBucket(0, 5, clock::get));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedOutboundRateBucket(20, Long.MAX_VALUE, clock::get));
        assertThrows(IllegalStateException.class,
                () -> new TrustedOutboundRateBucket(20, 5, () -> -1));
        var bucket = new TrustedOutboundRateBucket(20, 5, clock::get);
        assertThrows(IllegalArgumentException.class, () -> bucket.tryConsume(0));
        assertThrows(IllegalArgumentException.class, () -> bucket.tryConsume(6));
        assertThrows(IllegalArgumentException.class, () -> bucket.refundFailedSend(-1));
    }

    @Test
    void simultaneousSendsCannotOverspendInitialBurst() throws Exception {
        AtomicLong clock = new AtomicLong();
        var bucket = new TrustedOutboundRateBucket(20, 5, clock::get);
        var entered = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        var success = new AtomicInteger();
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                entered.countDown();
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (bucket.tryConsume(1)) success.incrementAndGet();
            });
            threads[i].start();
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Thread thread : threads) thread.join(2000);
            assertEquals(5, success.get());
            assertEquals(0L, bucket.remaining());
        } finally {
            start.countDown();
            for (Thread thread : threads) thread.join(2000);
        }
    }
}
