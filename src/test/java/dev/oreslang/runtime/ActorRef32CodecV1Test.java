package dev.oreslang.runtime;

/** Host-only unsigned Ref32 ABI smoke: not compiled AttoLang guest evidence. */
public final class ActorRef32CodecV1Test {
    private static void require(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    private static void must_reject(long offset) {
        try {
            ActorRef32CodecV1.encode_offset(offset);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid Ref32 offset accepted");
    }
    public static void main(String[] ignored) {
        final long[] candidates = {1L, 2L, 0x7fff_ffffL,
                0x8000_0000L, 0xffff_fffeL, 0xffff_ffffL};
        require(ActorRef32CodecV1.decode_word(0) == null, "zero is null");
        require(ActorRef32CodecV1.encode_offset(0) == 1, "first word");
        for (long value : candidates) {
            int word = (int) value;
            Long offset = ActorRef32CodecV1.decode_word(word);
            require(offset != null, "nonzero ref cannot be null");
            require(Integer.toUnsignedLong(ActorRef32CodecV1.encode_offset(offset)) == value,
                    "all unsigned high-bit words must round-trip");
        }
        require(ActorRef32CodecV1.decode_word(-1) ==
                ActorRef32CodecV1.MAX_ALIGNED_START, "unsigned UINT32_MAX");
        must_reject(-8);
        must_reject(1);
        must_reject(ActorRef32CodecV1.MAX_ALIGNED_START + 8);
        must_reject(Long.MAX_VALUE);
        System.out.println("PASS java-ref32-host-codec all unsigned boundaries");
    }
}
