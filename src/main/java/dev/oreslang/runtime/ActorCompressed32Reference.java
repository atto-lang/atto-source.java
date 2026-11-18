package dev.oreslang.runtime;

/**
 * Actor-relative 32-bit managed-reference word shared conceptually with the
 * LLVM compressed32 layout. This is NOT a native pointer or an ActorRef.
 *
 * <p>0 is null; other words encode 8-byte-aligned logical actor offsets as
 * (offset / 8) + 1. They must be decoded only inside the authenticated owning
 * actor's arena. Java int is the storage word, not a signed offset.</p>
 *
 * <p>These methods establish representation/bounds only. Object-start,
 * liveness, actor identity and borrow authorization are separate mandatory
 * checks performed by the owning runtime.</p>
 */
final class ActorCompressed32Reference {
    static final long MAX_OFFSET_BYTES = 0xffff_fffeL * Long.BYTES;
    static final long MAX_SPAN_BYTES = 0xffff_ffffL * Long.BYTES;
    private static final long U32_MASK = 0xffff_ffffL;

    private ActorCompressed32Reference() { }

    static int encode(long actorOffset) {
        if (actorOffset < 0 || actorOffset > MAX_OFFSET_BYTES
                || (actorOffset & (Long.BYTES - 1L)) != 0L) {
            throw new IllegalArgumentException("actor-relative offset cannot be compressed32");
        }
        return (int) ((actorOffset / Long.BYTES) + 1L);
    }

    static long decode(int encoded) {
        if (encoded == 0) {
            throw new SecurityException("null actor reference cannot be dereferenced");
        }
        return (Integer.toUnsignedLong(encoded) - 1L) * Long.BYTES;
    }

    /**
     * Decode a compact word stored in an existing 64-bit tagged-value cell.
     * The upper half must be zero. Future packed reference columns can store
     * just the low 32 bits with no padding or Java reference facade.
     */
    static long decodeStored(long word) {
        if ((word & ~U32_MASK) != 0L) {
            throw new SecurityException("invalid high bits in compressed actor reference");
        }
        return decode((int) word);
    }

    static long decodeChecked(long word, long logicalSpanBytes, long accessBytes) {
        long offset = decodeStored(word);
        if (logicalSpanBytes < 0 || logicalSpanBytes > MAX_SPAN_BYTES
                || accessBytes <= 0 || offset > logicalSpanBytes
                || accessBytes > logicalSpanBytes - offset) {
            throw new SecurityException("compressed actor reference outside logical arena");
        }
        return offset;
    }
}
