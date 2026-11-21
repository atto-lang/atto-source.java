package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted-host canonical scalar envelope bound to a compiler-issued actor
 * message-ABI fingerprint. This is a transitional SHARED-actor i64 codec,
 * NOT a compiler-generated arbitrary struct/record/tuple encoder and NOT
 * an authenticated remote message or worker attestation.
 *
 * <p>The route epoch and monotonic logical message sequence belong to the
 * bounded proxy mailbox, not to these immutable bytes. A queued frame must
 * survive a compatible V1 -> V2 route publication without re-encoding.
 * The stable logical UUID/incarnation and ABI fingerprint remain fixed.</p>
 */
final class SourceActorScalarWireV1 {
    static final int BYTE_LENGTH = 72;
    private static final int MAGIC = 0x4f524d31; // "ORM1"
    private static final byte VERSION = 1;
    private static final byte I64_TAG = 1;
    private static final int SHA256_BYTES = 32;

    record Binding(UUID logicalActorId, long incarnation, String messageAbiSha256) {
        Binding {
            Objects.requireNonNull(logicalActorId, "logicalActorId");
            if (incarnation <= 0L)
                throw new IllegalArgumentException("logical incarnation must be positive");
            Objects.requireNonNull(messageAbiSha256, "messageAbiSha256");
            if (messageAbiSha256.length() != SHA256_BYTES * 2)
                throw new IllegalArgumentException("compiler message ABI must be SHA-256 hex");
            for (int i = 0; i < messageAbiSha256.length(); ++i) {
                char ch = messageAbiSha256.charAt(i);
                if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')))
                    throw new IllegalArgumentException(
                            "compiler message ABI must use lowercase SHA-256 hex");
            }
        }
    }

    private SourceActorScalarWireV1() { }

    // Binding validates canonical lowercase SHA-256 once. Decode never
    // creates temporary digests or UUID objects per physical message.
    private static int hexNibble(char ch) {
        return ch <= '9' ? ch - '0' : ch - 'a' + 10;
    }

    static byte[] encodeLong(Binding binding, long value) {
        Objects.requireNonNull(binding, "binding");
        ByteBuffer buffer = ByteBuffer.allocate(BYTE_LENGTH).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(I64_TAG);
        buffer.putShort((short) 0); // reserved flags, must stay zero
        buffer.putLong(binding.logicalActorId().getMostSignificantBits());
        buffer.putLong(binding.logicalActorId().getLeastSignificantBits());
        buffer.putLong(binding.incarnation());
        String digest = binding.messageAbiSha256();
        for (int i = 0; i < SHA256_BYTES; ++i) {
            int j = i * 2;
            buffer.put((byte) ((hexNibble(digest.charAt(j)) << 4)
                    | hexNibble(digest.charAt(j + 1))));
        }
        buffer.putLong(value);
        return buffer.array();
    }

    static long decodeLong(Binding binding, byte[] wire) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(wire, "wire");
        if (wire.length != BYTE_LENGTH)
            throw new SecurityException("invalid compiler-bound scalar wire length");
        ByteBuffer buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION
                || buffer.get() != I64_TAG || buffer.getShort() != 0)
            throw new SecurityException("invalid compiler-bound scalar frame header");
        // Check every fixed header field and all 32 digest bytes, without
        // short-circuiting on a matching prefix. The transport stays bounded
        // and untrusted bytes cannot cause per-dispatch digest allocations.
        long mismatch = buffer.getLong()
                ^ binding.logicalActorId().getMostSignificantBits();
        mismatch |= buffer.getLong()
                ^ binding.logicalActorId().getLeastSignificantBits();
        mismatch |= buffer.getLong() ^ binding.incarnation();
        String digest = binding.messageAbiSha256();
        for (int i = 0; i < SHA256_BYTES; ++i) {
            int j = i * 2;
            int expected = (hexNibble(digest.charAt(j)) << 4)
                    | hexNibble(digest.charAt(j + 1));
            mismatch |= (buffer.get() & 0xff) ^ expected;
        }
        if (mismatch != 0L)
            throw new SecurityException(
                    "foreign logical actor, incarnation or compiler message ABI");
        return buffer.getLong();
    }
}
