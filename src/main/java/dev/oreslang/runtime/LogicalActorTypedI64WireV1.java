package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted-host, canonical ABI-bound i64 actor-mail frame V1.
 *
 * <p>This is an intentionally limited first typed-wire slice: the compiler
 * produces the owning actor's message ABI SHA-256 and the runtime binds it to
 * a stable logical actor UUID/incarnation. There is exactly one supported
 * payload type (signed 64-bit integer). A general compiler-generated codec
 * for records, structs, enums, tuples and message unions is still required.
 * This frame does not authorize a cross-process/untrusted worker transfer.</p>
 *
 * <p>The proxy mailbox owns the dispatch route epoch independently because
 * admitted bytes may remain queued across a compatible route upgrade. The
 * envelope is not an authentication token; all ownership checks take place
 * in the trusted physical router before delivery.</p>
 */
final class LogicalActorTypedI64WireV1 {
    private static final int MAGIC = 0x4f524d31; // "ORM1"
    private static final byte VERSION = 1;
    private static final byte TYPE_I64 = 1;
    static final int FRAME_BYTES = Integer.BYTES + 2
            + 2 * Long.BYTES + Long.BYTES + 32 + Long.BYTES;

    private LogicalActorTypedI64WireV1() { }

    static String canonicalAbi(String sha256) {
        Objects.requireNonNull(sha256, "messageAbiSha256");
        if (sha256.length() != 64)
            throw new SecurityException("actor message ABI digest must be SHA-256");
        for (int i = 0; i < sha256.length(); i++) {
            char c = sha256.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
                throw new SecurityException("actor message ABI digest must be lowercase hex");
        }
        return sha256;
    }

    static byte[] encode(
            UUID actorId, long incarnation, String messageAbiSha256, long value) {
        Objects.requireNonNull(actorId, "actorId");
        if (incarnation <= 0L)
            throw new IllegalArgumentException("logical actor incarnation must be positive");
        byte[] abi = HexFormat.of().parseHex(canonicalAbi(messageAbiSha256));
        ByteBuffer out = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(VERSION).put(TYPE_I64);
        out.putLong(actorId.getMostSignificantBits());
        out.putLong(actorId.getLeastSignificantBits());
        out.putLong(incarnation);
        out.put(abi);
        out.putLong(value);
        return out.array();
    }

    static long decode(
            byte[] input, UUID expectedActorId, long expectedIncarnation,
            String expectedMessageAbiSha256) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedActorId, "expectedActorId");
        if (expectedIncarnation <= 0L)
            throw new IllegalArgumentException("expected incarnation must be positive");
        byte[] expectedAbi = HexFormat.of().parseHex(
                canonicalAbi(expectedMessageAbiSha256));
        // Reject truncation and trailing bytes before allocating/decoding
        // any guest-visible value. The wire is exactly one canonical i64.
        if (input.length != FRAME_BYTES)
            throw new SecurityException("invalid ABI-bound i64 frame length");
        ByteBuffer in = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN);
        if (in.getInt() != MAGIC || in.get() != VERSION || in.get() != TYPE_I64)
            throw new SecurityException("unsupported actor mail wire version or type");
        if (in.getLong() != expectedActorId.getMostSignificantBits()
                || in.getLong() != expectedActorId.getLeastSignificantBits())
            throw new SecurityException("foreign logical actor mail identity");
        if (in.getLong() != expectedIncarnation)
            throw new SecurityException("stale actor incarnation in typed mail");
        // Fixed-work digest comparison; no early-exit on a matching prefix.
        int mismatch = 0;
        for (byte expected : expectedAbi)
            mismatch |= (in.get() ^ expected) & 0xff;
        if (mismatch != 0)
            throw new SecurityException("compiler message ABI digest mismatch");
        return in.getLong();
    }
}
