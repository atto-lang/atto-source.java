package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native tagged REFERENCE slots store the cross-backend compressed32 word
 * (0 = null, actor offset / 8 + 1) with a zero upper half, never a host
 * pointer or raw 64-bit offset. Host-only runtime evidence: this is not a
 * compiled AttoLang guest, Native Image receipt or 32-bit JVM execution.
 */
final class ActorNativeArenaCompressed32Test {
    private static final Object UNINIT = new Object();
    private static final long REFERENCE_TAG = 11L;

    /** Referenceable target: an issued native object (fields facade). */
    private static ActorNativeArena.NativeValue object(ActorNativeArena arena) {
        return (ActorNativeArena.NativeValue) arena.fields(List.of("v"), UNINIT);
    }

    private static ActorNativeArena arena(long limit) {
        return new ActorNativeArena(limit, () -> { }, bytes -> () -> { });
    }

    private static long raw(ActorNativeArena arena, long slot, int field) throws Exception {
        Method read = ActorNativeArena.class.getDeclaredMethod("read", long.class, int.class);
        read.setAccessible(true);
        return (long) read.invoke(arena, slot, field);
    }

    private static void writeRaw(ActorNativeArena arena, long slot, int field, long value) throws Exception {
        Method write = ActorNativeArena.class.getDeclaredMethod("write", long.class, int.class, long.class);
        write.setAccessible(true);
        write.invoke(arena, slot, field, value);
    }

    @Test
    void referenceSlotStoresCompressedWordWithZeroHighHalf() throws Exception {
        try (ActorNativeArena arena = arena(64 * 1024)) {
            ActorNativeArena.ValueCell holder = arena.valueCell(null, UNINIT);
            // Push the target past the first slab so its offset is nontrivial.
            for (int i = 0; i < 200; i++) arena.valueCell(7L, UNINIT);
            ActorNativeArena.NativeValue target = object(arena);
            long targetOffset = target.nativeOffset();
            assertEquals(0L, targetOffset & 7L, "every issued native value is 8-aligned");
            assertTrue(targetOffset >= 4096, "target should live beyond the first slab");

            assertTrue(holder.trySet(target));
            long slot = holder.nativeOffset();
            assertEquals(REFERENCE_TAG, raw(arena, slot, 0));
            long word = raw(arena, slot, 8);
            assertEquals(0L, word >>> 32, "compressed32 word must leave the upper half zero");
            assertEquals(Integer.toUnsignedLong(ActorCompressed32Reference.encode(targetOffset)), word);
            assertEquals(targetOffset / 8 + 1, word, "shared LLVM/.NET/Wasm ref32 word");
            assertSame(target, holder.get());
        }
    }

    @Test
    void tamperedHighBitsOrForgedWordsFailClosed() throws Exception {
        try (ActorNativeArena arena = arena(64 * 1024)) {
            ActorNativeArena.ValueCell holder = arena.valueCell(null, UNINIT);
            ActorNativeArena.NativeValue target = object(arena);
            assertTrue(holder.trySet(target));
            long slot = holder.nativeOffset();
            long word = raw(arena, slot, 8);

            writeRaw(arena, slot, 8, word | (1L << 32));
            assertThrows(SecurityException.class, holder::get, "high bits must never be ignored");

            writeRaw(arena, slot, 8, 0L);
            assertThrows(SecurityException.class, holder::get, "word 0 is null, not offset 0");

            // A well-formed word that the arena never issued is not authority.
            writeRaw(arena, slot, 8, word + 1);
            assertThrows(SecurityException.class, holder::get);

            writeRaw(arena, slot, 8, word);
            assertSame(target, holder.get());
        }
    }

    @Test
    void identicalWordsInTwoActorArenasGrantNoCrossActorAuthority() throws Exception {
        try (ActorNativeArena first = arena(16 * 1024);
             ActorNativeArena second = arena(16 * 1024)) {
            ActorNativeArena.ValueCell firstHolder = first.valueCell(null, UNINIT);
            ActorNativeArena.ValueCell secondHolder = second.valueCell(null, UNINIT);
            ActorNativeArena.NativeValue firstTarget = object(first);
            ActorNativeArena.NativeValue secondTarget = object(second);
            assertEquals(firstTarget.nativeOffset(), secondTarget.nativeOffset(),
                    "independent actor address spaces reuse the same offsets");

            assertTrue(firstHolder.trySet(firstTarget));
            assertTrue(secondHolder.trySet(secondTarget));
            assertEquals(raw(first, firstHolder.nativeOffset(), 8),
                    raw(second, secondHolder.nativeOffset(), 8), "same compressed32 word");
            assertSame(firstTarget, firstHolder.get());
            assertSame(secondTarget, secondHolder.get());

            // Decoding is arena-relative: a foreign actor's value cannot be stored.
            assertThrows(SecurityException.class, () -> firstHolder.trySet(secondTarget));
            assertSame(firstTarget, firstHolder.get(), "rejected store left the old slot intact");
        }
    }

    @Test
    void overwriteAndClearKeepReferenceAccountingByDecodedOffset() {
        try (ActorNativeArena arena = arena(64 * 1024)) {
            Map<String, Object> object = arena.fields(List.of("left", "right"), UNINIT);
            ActorNativeArena.NativeValue a = object(arena);
            ActorNativeArena.NativeValue b = arena.nativeList();
            object.put("left", a);
            object.put("right", a);
            object.put("left", b);   // a still referenced by right
            assertSame(a, object.get("right"));
            assertSame(b, object.get("left"));
            object.put("right", null);
            object.put("left", 9L);  // releases b
            assertNull(object.get("right"));
            assertEquals(9L, object.get("left"));
            object.put("right", a);  // re-reference after release
            assertSame(a, object.get("right"));
        }
    }

    @Test
    void everyNativeValueFamilyHasANaturallyAlignedEncodableOffset() {
        try (ActorNativeArena arena = arena(256 * 1024)) {
            List<ActorNativeArena.NativeValue> values = List.of(
                    arena.valueCell(1L, UNINIT),
                    (ActorNativeArena.NativeValue) arena.fields(List.of("x"), UNINIT),
                    arena.nativeList(),
                    arena.taskFrame(),
                    arena.region(24),
                    arena.valueCell("text payload", UNINIT));
            for (ActorNativeArena.NativeValue value : values) {
                long offset = value.nativeOffset();
                assertEquals(0L, offset & 7L, value.getClass().getSimpleName() + " offset not 8-aligned");
                assertEquals(offset, ActorCompressed32Reference.decode(ActorCompressed32Reference.encode(offset)));
            }
        }
    }
}
