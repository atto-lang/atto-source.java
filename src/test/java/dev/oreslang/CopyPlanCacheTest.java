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
