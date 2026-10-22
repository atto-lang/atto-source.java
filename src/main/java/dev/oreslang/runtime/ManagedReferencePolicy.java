package dev.oreslang.runtime;

import java.util.OptionalLong;

/**
 * Cross-backend managed-reference storage policy for the Java/Graal runtime.
 *
 * <p>Ordinary Java object references remain VM-managed: HotSpot or Native
 * Image owns their physical representation. Oreslang-controlled data-oriented
 * storage uses primitive 32-bit indices/offsets so SoA/AoSoA/ECS layouts do
 * not depend on the host VM's oop width.</p>
 */
public final class ManagedReferencePolicy {
    /** Zero is reserved for null, leaving 0..2^32-2 as logical indices. */
    public static final long MAX_DENSE_INDEX32 = 0xffff_fffeL;

    private ManagedReferencePolicy() { }

    public static NativeBuildMode.ManagedReferenceMode hostManagedReferenceMode() {
        return NativeBuildMode.managedReferenceMode();
    }

    public static int denseIndexStorageBytes() {
        return Integer.BYTES;
    }

    /**
     * Encode a logical non-null table index into exactly 32 bits.
     *
     * <p>The returned Java int is an unsigned storage word. Values above
     * Integer.MAX_VALUE therefore appear negative if interpreted as signed
     * Java integers; callers must preserve the encoded bits.</p>
     */
    public static int encodeDenseIndex32(long index) {
        if (index < 0 || index > MAX_DENSE_INDEX32) {
            throw new IllegalArgumentException("dense32 index outside unsigned compact range");
        }
        return (int) (index + 1L);
    }

    /** Decode zero as null and all other 32-bit patterns as unsigned indices. */
    public static OptionalLong decodeDenseIndex32(int encoded) {
        if (encoded == 0) return OptionalLong.empty();
        return OptionalLong.of(Integer.toUnsignedLong(encoded) - 1L);
    }

    /**
     * Eight-byte-quantized actor-region offset, interoperable with LLVM's
     * CompressedActorRef codec. This is NOT a Java oop, native address, lifetime
     * proof, or actor capability. The owning region and live allocation must
     * still be authenticated before any access.
     *
     * 0 = null; 1 = byte offset 0; 0xffffffff = byte offset
     * (2^32 - 2) * 8. The maximum representable region span is 32 GiB - 8.
     */
    public static final int ARENA_REF_ALIGNMENT_SHIFT = 3;
    public static final long MAX_ARENA_OFFSET32 = 0xffff_fffeL << ARENA_REF_ALIGNMENT_SHIFT;
    public static final long MAX_ARENA_SPAN32 = 0xffff_ffffL << ARENA_REF_ALIGNMENT_SHIFT;

    private static void requireArenaSpan32(long arenaBytes) {
        if (arenaBytes <= 0 || arenaBytes > MAX_ARENA_SPAN32) {
            throw new IllegalArgumentException("actor region exceeds compressed32 span");
        }
    }

    public static int encodeArenaOffset32(long byteOffset, long arenaBytes) {
        requireArenaSpan32(arenaBytes);
        if (byteOffset < 0 || byteOffset >= arenaBytes
                || byteOffset > MAX_ARENA_OFFSET32 || (byteOffset & 7L) != 0) {
            throw new IllegalArgumentException("actor region offset is out of bounds or unaligned");
        }
        return (int) ((byteOffset >>> ARENA_REF_ALIGNMENT_SHIFT) + 1);
    }

    public static OptionalLong decodeArenaOffset32(int encoded, long arenaBytes) {
        if (encoded == 0) return OptionalLong.empty();
        requireArenaSpan32(arenaBytes);
        long offset = (Integer.toUnsignedLong(encoded) - 1L) << ARENA_REF_ALIGNMENT_SHIFT;
        if (offset >= arenaBytes) {
            throw new IllegalArgumentException("compressed32 reference outside actor region");
        }
        return OptionalLong.of(offset);
    }

    public static boolean isNullDenseIndex32(int encoded) {
        return encoded == 0;
    }
}
