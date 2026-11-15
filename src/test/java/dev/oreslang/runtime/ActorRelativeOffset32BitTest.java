package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only allocator arithmetic: not a compiled AttoLang guest or Native Image
 * receipt. Verify that arena-relative offsets cannot silently widen to 64 bits.
 */
final class ActorRelativeOffset32BitTest {
    @Test
    void allocatorsAreIndependentAndRejectU32WrapWithoutAdvancingState() throws Exception {
        Method reserve = ActorNativeArena.class.getDeclaredMethod(
                "reserveActorOffset", int.class, int.class);
        reserve.setAccessible(true);
        Field cursor = ActorNativeArena.class.getDeclaredField("nextActorOffset");
        cursor.setAccessible(true);

        try (var first = new ActorNativeArena(1024, () -> { }, bytes -> () -> { });
             var second = new ActorNativeArena(1024, () -> { }, bytes -> () -> { })) {
            // Identical actor-local offsets never mean identical host memory.
            assertEquals(0L, (long) reserve.invoke(first, 8, 8));
            assertEquals(0L, (long) reserve.invoke(second, 8, 8));
            assertEquals(8L, cursor.getLong(first));
            assertEquals(8L, cursor.getLong(second));

            // The last 8 bytes in an unsigned 32-bit address space are valid.
            cursor.setLong(first, (1L << 32) - 8);
            assertEquals((1L << 32) - 8, (long) reserve.invoke(first, 8, 8));
            assertEquals(1L << 32, cursor.getLong(first));

            // No 33rd address bit, even if the host Java VM uses 64-bit refs.
            InvocationTargetException exhausted = assertThrows(
                    InvocationTargetException.class,
                    () -> reserve.invoke(first, 1, 8));
            assertInstanceOf(IllegalStateException.class, exhausted.getCause());
            assertTrue(exhausted.getCause().getMessage().contains("32-bit offset"));
            assertEquals(1L << 32, cursor.getLong(first), "failed admission advanced actor cursor");
            // An empty reservation must not publish the unrepresentable
            // one-past-end offset as a guest pointer either.
            assertThrows(InvocationTargetException.class,
                    () -> reserve.invoke(first, 0, 8));
            assertEquals(1L << 32, cursor.getLong(first));
            assertEquals(8L, cursor.getLong(second), "other actor cursor mutated");

            // Alignment itself must be checked before reserving bytes.
            cursor.setLong(first, (1L << 32) - 3);
            assertThrows(InvocationTargetException.class, () -> reserve.invoke(first, 1, 8));
            assertEquals((1L << 32) - 3, cursor.getLong(first));
            assertThrows(InvocationTargetException.class, () -> reserve.invoke(first, 8, 3));
            assertEquals((1L << 32) - 3, cursor.getLong(first));
        }

    }

    @Test
    void invalidReservationsAndHostLongOverflowLeaveCursorUntouched() throws Exception {
        Method reserve = ActorNativeArena.class.getDeclaredMethod(
                "reserveActorOffset", int.class, int.class);
        reserve.setAccessible(true);
        Field cursor = ActorNativeArena.class.getDeclaredField("nextActorOffset");
        cursor.setAccessible(true);

        try (var arena = new ActorNativeArena(1024, () -> { }, bytes -> () -> { })) {
            cursor.setLong(arena, 16L);
            for (int[] request : new int[][] {{-1, 8}, {8, 0}, {8, 3}, {8, -8}}) {
                InvocationTargetException failure = assertThrows(
                        InvocationTargetException.class,
                        () -> reserve.invoke(arena, request[0], request[1]));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                assertEquals(16L, cursor.getLong(arena), "invalid admission changed offset");
            }

            // Even a corrupted/unrepresentable host-long cursor may not wrap.
            cursor.setLong(arena, Long.MAX_VALUE - 3);
            InvocationTargetException overflow = assertThrows(
                    InvocationTargetException.class, () -> reserve.invoke(arena, 8, 8));
            assertInstanceOf(ArithmeticException.class, overflow.getCause());
            assertEquals(Long.MAX_VALUE - 3, cursor.getLong(arena),
                    "arithmetic failure advanced the monotonic cursor");
        }
    }
}
