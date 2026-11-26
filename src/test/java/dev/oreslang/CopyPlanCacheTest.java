package dev.oreslang;

import dev.oreslang.types.CopyPlanCache;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class CopyPlanCacheTest {
    @Test
    void cachesByIdentityAndNotStructuralEquality() {
        var cache = new CopyPlanCache<String>(2, 10);
        var first = new String("type");
        var second = new String("type");
        var calls = new AtomicInteger();
        assertEquals("a", cache.getOrCompute(first, () -> { calls.incrementAndGet(); return "a"; }, String::length));
        assertEquals("a", cache.getOrCompute(first, () -> { fail("cache miss"); return "bad"; }, String::length));
        assertEquals("b", cache.getOrCompute(second, () -> "b", String::length));
        assertEquals(1, calls.get());
        assertEquals(2, cache.size());
    }

    @Test
    void concurrentMissesPublishOnePlanAndRespectBothLimits() throws Exception {
        var cache = new CopyPlanCache<Object>(64, 128);
        var same = new Object();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var barrier = new java.util.concurrent.CyclicBarrier(8);
            var results = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> cache.getOrCompute(same, () -> {
                    try {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return new Object();
                }, p -> 2)));
            }
            Object plan = results.getFirst().get(10, java.util.concurrent.TimeUnit.SECONDS);
            for (var result : results) {
                assertSame(plan, result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(1, cache.size());
            assertEquals(2, cache.weight());
            var other = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 256; i++) {
                other.add(pool.submit(() -> cache.getOrCompute(new Object(), Object::new, p -> 2)));
            }
            for (var result : other) result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(64, cache.size());
            assertEquals(128, cache.weight());
        }
    }

    @Test
    void weightAndEntryLimitsAndFailuresNeverPoisonCache() {
        var cache = new CopyPlanCache<String>(2, 4);
        var key = new Object();
        assertThrows(IllegalStateException.class, () ->
                cache.getOrCompute(key, () -> { throw new IllegalStateException(); }, String::length));
        assertEquals(0, cache.size());
        assertEquals("abc", cache.getOrCompute(key, () -> "abc", String::length));
        var second = new Object();
        assertEquals("too big", cache.getOrCompute(second, () -> "too big", String::length));
        assertEquals(1, cache.size());
        assertEquals("d", cache.getOrCompute(second, () -> "d", String::length));
        assertEquals(4, cache.weight());
        assertEquals("x", cache.getOrCompute(new Object(), () -> "x", String::length));
        assertEquals(2, cache.size());
    }
}
