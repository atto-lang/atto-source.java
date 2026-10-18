package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-only canonical, bounded checkpoint envelope. This serializes an
 * ALREADY-VERIFIED byte snapshot, not Java objects/closures/FFI capabilities.
 * An unkeyed SHA-256 trailer detects accidental corruption, NOT authenticity.
 *
 * Compiler schema receipts and isolated state snapshot/restore are separate
 * prerequisites; never treat a decoded envelope as authorization to execute.
 */
final class ActorCheckpointEnvelopeV1 {
    private static final byte[] MAGIC = "ORESCP01".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_STATE_BYTES = 1_048_576;
    private static final int HASH_BYTES = 32;
    private static final int HEADER_BYTES = 8 + 4 + 16 + 8 + 4 * HASH_BYTES + 4;
    private static final int TRAILER_BYTES = HASH_BYTES;

    record Header(UUID actorId, long incarnation, String codeDigest,
                  String stateSchemaDigest, String messageAbiDigest,
                  String capabilityDigest) {
        Header {
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
            checkHash(codeDigest);
            checkHash(stateSchemaDigest);
            checkHash(messageAbiDigest);
            checkHash(capabilityDigest);
        }

        boolean compatibleWith(Header expected) {
            return actorId.equals(expected.actorId)
                    && incarnation == expected.incarnation
                    && stateSchemaDigest.equals(expected.stateSchemaDigest)
                    && messageAbiDigest.equals(expected.messageAbiDigest)
                    && capabilityDigest.equals(expected.capabilityDigest);
        }
    }

    record Snapshot(Header header, byte[] stateBytes) {
        Snapshot {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(stateBytes, "stateBytes");
            if (stateBytes.length > MAX_STATE_BYTES)
                throw new IllegalArgumentException("actor checkpoint exceeds byte budget");
            stateBytes = stateBytes.clone();
        }

        @Override public byte[] stateBytes() { return stateBytes.clone(); }
    }

    private ActorCheckpointEnvelopeV1() { }

    static byte[] encode(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Header h = snapshot.header();
        byte[] state = snapshot.stateBytes();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + state.length + TRAILER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).putInt(1);
        out.putLong(h.actorId().getMostSignificantBits());
        out.putLong(h.actorId().getLeastSignificantBits());
        out.putLong(h.incarnation());
        putDigest(out, h.codeDigest());
        putDigest(out, h.stateSchemaDigest());
        putDigest(out, h.messageAbiDigest());
        putDigest(out, h.capabilityDigest());
        out.putInt(state.length).put(state);
        byte[] bytes = out.array();
        byte[] checksum = sha256(bytes, 0, HEADER_BYTES + state.length);
        out.put(checksum);
        return bytes;
    }

    /**
     * Rejects corruption, truncation, trailing bytes, foreign actors, previous
     * incarnations, wrong ABI/capabilities, and unapproved code digests.
     */
    static Snapshot decode(byte[] encoded, Header expected) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(expected, "expected");
        if (encoded.length < HEADER_BYTES + TRAILER_BYTES
                || encoded.length > HEADER_BYTES + MAX_STATE_BYTES + TRAILER_BYTES)
            throw new IllegalArgumentException("invalid actor checkpoint byte length");
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC) || in.getInt() != 1)
            throw new IllegalArgumentException("invalid actor checkpoint magic/version");
        UUID id = new UUID(in.getLong(), in.getLong());
        long incarnation = in.getLong();
        Header decoded = new Header(id, incarnation, getDigest(in), getDigest(in),
                getDigest(in), getDigest(in));
        int size = in.getInt();
        if (size < 0 || size > MAX_STATE_BYTES
                || HEADER_BYTES + (long) size + TRAILER_BYTES != encoded.length)
            throw new IllegalArgumentException("invalid actor checkpoint payload length");
        if (!decoded.compatibleWith(expected)
                || !decoded.codeDigest().equals(expected.codeDigest()))
            throw new SecurityException("actor checkpoint identity/ABI/code mismatch");
        byte[] computed = sha256(encoded, 0, HEADER_BYTES + size);
        byte[] actual = Arrays.copyOfRange(encoded, HEADER_BYTES + size, encoded.length);
        if (!MessageDigest.isEqual(computed, actual))
            throw new SecurityException("actor checkpoint checksum mismatch");
        byte[] state = new byte[size];
        in.get(state);
        return new Snapshot(decoded, state);
    }

    private static void checkHash(String hex) {
        if (hex == null || hex.length() != 64)
            throw new IllegalArgumentException("expected lowercase SHA-256");
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')))
                throw new IllegalArgumentException("noncanonical SHA-256 hex");
        }
    }

    private static void putDigest(ByteBuffer out, String hex) {
        for (int i = 0; i < 64; i += 2) {
            out.put((byte) Integer.parseInt(hex.substring(i, i + 2), 16));
        }
    }

    private static String getDigest(ByteBuffer in) {
        char[] text = new char[64];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < HASH_BYTES; i++) {
            int value = Byte.toUnsignedInt(in.get());
            text[2 * i] = alphabet[value >>> 4];
            text[2 * i + 1] = alphabet[value & 15];
        }
        return new String(text);
    }

    private static byte[] sha256(byte[] bytes, int start, int length) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(bytes, start, length);
            return hash.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
