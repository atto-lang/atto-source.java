package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

final class ReusableLongStateRegionTest {
    private static final LongUnaryOperator INCREMENT = value -> value + 1;

    @Test
    void inPlaceScalarOpsReuseBackingAndRejectStaleAndForeignHandles() {
        try (var region = new ReusableLongStateRegion(2);
             var foreign = new ReusableLongStateRegion(2)) {
            long a = region.insert(0);
            long b = region.insert(8);
            assertNotEquals(a, foreign.insert(0));
            assertThrows(IllegalStateException.class, () -> foreign.read(a));
            assertThrows(IllegalStateException.class, () -> region.read(b ^ (1L << 40)));
            assertThrows(IllegalStateException.class, () -> region.insert(9));
            for (int i = 0; i < 100_000; i++) {
                region.update(a, INCREMENT);
            }
            assertEquals(100_000L, region.read(a));
            region.write(b, 41);
            assertEquals(41L, region.read(b));
            region.remove(a);
            assertThrows(IllegalStateException.class, () -> region.read(a));
            long replaced = region.insert(42);
            assertNotEquals(a, replaced);
            assertEquals(42L, region.read(replaced));
            assertThrows(IllegalStateException.class, () -> region.write(a, 7));
        }
    }

    @Test
    void callbackCannotReenterOrRetireStorageWhileWriting() {
        var region = new ReusableLongStateRegion(1);
        long h = region.insert(42);
        region.update(h, value -> {
            assertThrows(IllegalStateException.class, () -> region.read(h));
            assertThrows(IllegalStateException.class, () -> region.write(h, 4));
            assertThrows(IllegalStateException.class, () -> region.remove(h));
            assertFalse(region.tryClose());
            assertTrue(region.isRetiring());
            return value + 1;
        });
        assertEquals(1, region.size());
        assertThrows(IllegalStateException.class, () -> region.read(h));
        assertTrue(region.tryClose());
        assertTrue(region.isClosed());
    }

    @Test
    void throwingOperationReleasesWriteGate() {
        try (var region = new ReusableLongStateRegion(1)) {
            long h = region.insert(11);
            assertThrows(IllegalArgumentException.class, () ->
                    region.update(h, value -> { throw new IllegalArgumentException("test"); }));
            assertEquals(11, region.read(h));
            region.update(h, INCREMENT);
            assertEquals(12, region.read(h));
        }
    }

    @Test
    void retirementFencesAccessAndReclaimIsIdempotent() {
        var region = new ReusableLongStateRegion(1);
        long h = region.insert(7);
        region.beginRetire();
        assertTrue(region.isRetiring());
        assertThrows(IllegalStateException.class, () -> region.read(h));
        assertThrows(IllegalStateException.class, () -> region.insert(9));
        assertTrue(region.tryClose());
        assertTrue(region.tryClose());
        assertEquals(0, region.size());
        assertThrows(IllegalStateException.class, () -> region.update(h, INCREMENT));
    }

    @Test
    void checkedInPlaceAddIsTransactionalAtBothSignedBounds() {
        try (var region = new ReusableLongStateRegion(2)) {
            long upper = region.insert(Long.MAX_VALUE - 1);
            long lower = region.insert(Long.MIN_VALUE + 1);
            assertEquals(Long.MAX_VALUE, region.addExact(upper, 1));
            assertEquals(Long.MIN_VALUE, region.addExact(lower, -1));
            assertThrows(ArithmeticException.class, () -> region.addExact(upper, 1));
            assertThrows(ArithmeticException.class, () -> region.addExact(lower, -1));
            assertEquals(Long.MAX_VALUE, region.read(upper));
            assertEquals(Long.MIN_VALUE, region.read(lower));
            assertEquals(Long.MAX_VALUE - 2, region.addExact(upper, -2));
            region.remove(upper);
            assertThrows(IllegalStateException.class, () -> region.addExact(upper, 1));
            region.beginRetire();
            assertThrows(IllegalStateException.class, () -> region.addExact(lower, 1));
        }
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ReusableLongStateRegion(0));
        assertThrows(IllegalArgumentException.class, () -> new ReusableLongStateRegion(65_537));
    }
}
