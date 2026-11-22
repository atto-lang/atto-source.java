package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOST-ONLY fault-injection tests for actor-local logical offset rollback.
 * The injected allocator fails before JNI, so no GraalVM/Native Image/guest
 * execution or actual native allocation is being certified here.
 */
final class ActorNativeArenaOffsetRollbackTest {
    private static long privateLong(ActorNativeArena arena, String fieldName)
            throws Exception {
        Field field = ActorNativeArena.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(arena);
    }

    private static ActorNativeArena.Budget tracked(AtomicLong charged) {
        return bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        };
    }

    @Test
    void failedNativeSlabAllocationDoesNotBurnLogicalOffsetsOrNativeCharges()
            throws Exception {
        AtomicLong charged = new AtomicLong();
        AtomicLong invocations = new AtomicLong();
        ActorNativeArena.NativeAdmissionProbe denied = bytes -> {
            invocations.incrementAndGet();
            throw new OutOfMemoryError("injected native admission failure");
        };
        try (var arena = new ActorNativeArena(256, () -> {}, tracked(charged), denied)) {
            for (int i = 0; i < 1_024; i++) {
                var error = assertThrows(OutOfMemoryError.class,
                        () -> arena.fields(List.of("field"), null));
                assertTrue(error.getMessage().contains("injected"));
                assertEquals(0L, privateLong(arena, "nextActorOffset"),
                        "a failed never-published slab must not burn its offset");
                assertEquals(0L, privateLong(arena, "committed"),
                        "failed slab must not commit native payload");
                assertEquals(0L, charged.get(), "failed slab must refund its budget");
            }
        }
        assertEquals(1_024L, invocations.get());
        assertEquals(0L, charged.get());
    }

    @Test
    void runtimeFaultAlsoRollsBackReservedSlabOffset()
            throws Exception {
        AtomicLong charged = new AtomicLong();
        try (var arena = new ActorNativeArena(128, () -> {}, tracked(charged),
                bytes -> { throw new IllegalStateException("injected runtime admission fault"); })) {
            for (int i = 0; i < 32; i++) {
                assertThrows(IllegalStateException.class,
                        () -> arena.fields(List.of("value"), null));
                assertEquals(0L, privateLong(arena, "nextActorOffset"));
                assertEquals(0L, charged.get());
            }
        }
    }

    @Test
    void failedNativeRegionAllocationLeavesActorOffsetAndOtherStateUntouched()
            throws Exception {
        AtomicLong charged = new AtomicLong();
        try (var arena = new ActorNativeArena(256, () -> {}, tracked(charged),
                bytes -> { throw new OutOfMemoryError("injected region failure"); })) {
            for (int i = 0; i < 128; i++) {
                assertThrows(OutOfMemoryError.class, () -> arena.region(16));
                assertEquals(0L, privateLong(arena, "nextActorOffset"));
                assertEquals(0L, privateLong(arena, "committed"));
                assertEquals(0L, privateLong(arena, "allocated"));
                assertEquals(0L, charged.get());
            }
        }
    }
}
