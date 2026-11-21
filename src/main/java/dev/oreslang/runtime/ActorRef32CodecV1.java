package dev.oreslang.runtime;

/**
 * Versioned, unsigned actor-local offset8+1 Ref32 storage ABI only.
 *
 * This codec is NOT an object capability, ownership proof, cross-actor token,
 * native MemorySegment, or a promise of compiled AttoLang guest lowering.
 * An execution domain must separately validate actor incarnation, object start,
 * slot generation, type, bounds, permissions and live borrow authority.
 */
public final class ActorRef32CodecV1 {
    public static final long MAX_ALIGNED_START = (0xffff_ffffL - 1L) * 8L;

    private ActorRef32CodecV1() {}

    /** Encodes a non-null, eight-byte-aligned actor-relative logical offset. */
    public static int encode_offset(long byteOffset) {
        if (byteOffset < 0 || byteOffset > MAX_ALIGNED_START || (byteOffset % 8L) != 0L)
            throw new IllegalArgumentException("E_ACTOR_REF32_OFFSET");
        return (int) (byteOffset / 8L + 1L);
    }

    /** Returns null only for the null word (0); other words preserve high bits. */
    public static Long decode_word(int word) {
        if (word == 0) return null;
        return (Integer.toUnsignedLong(word) - 1L) * 8L;
    }
}
