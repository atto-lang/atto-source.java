package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Canonical, bounded v1 DATA-only wire profile for
 * Option<Result<int,string>> crossing an actor mailbox boundary.
 *
 * This is a transport codec, not compiler lowering or a supervisor CONTROL
 * interface. Never interpret these bytes as an authorized failure notice.
 * Actor generation and mailbox admission are checked by the host envelope.
 */
public final class ActorAdtDataWireV1 {
    private static final byte[] MAGIC = {0x41, 0x54, 0x41, 0x44}; // ATAD
    private static final int VERSION = 1, FAMILY = 1;
    private static final int HEADER = 7, MAX_ERROR_BYTES = 256;
    private static final int MAX_WIRE_BYTES = HEADER + 2 + MAX_ERROR_BYTES;

    private ActorAdtDataWireV1() { }

    public sealed interface Value permits None, SomeOk, SomeErr { }
    public record None() implements Value { }
    public record SomeOk(long value) implements Value { }
    public record SomeErr(String error) implements Value {
        public SomeErr { utf8(error); }
    }

    private static byte[] utf8(String input) {
        Objects.requireNonNull(input, "ADT error payload");
        if (input.length() > MAX_ERROR_BYTES)
            throw new IllegalArgumentException("ADT error exceeds character budget");
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(input));
            if (encoded.remaining() > MAX_ERROR_BYTES)
                throw new IllegalArgumentException("ADT error exceeds byte budget");
            var bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("invalid ADT error UTF-8", malformed);
        }
    }

    public static byte[] encode(Value value) {
        Objects.requireNonNull(value, "ADT value");
        int tag;
        byte[] payload;
        if (value instanceof None) {
            tag = 0; payload = new byte[0];
        } else if (value instanceof SomeOk ok) {
            tag = 1;
            payload = new byte[8];
            long n = ok.value();
            for (int i = 0; i < 8; ++i) payload[i] = (byte) (n >>> (i * 8));
        } else if (value instanceof SomeErr err) {
            tag = 2;
            byte[] encoded = utf8(err.error());
            payload = new byte[2 + encoded.length];
            payload[0] = (byte) encoded.length;
            payload[1] = (byte) (encoded.length >>> 8);
            System.arraycopy(encoded, 0, payload, 2, encoded.length);
        } else {
            throw new IllegalArgumentException("unknown ADT variant");
        }
        byte[] output = new byte[HEADER + payload.length];
        System.arraycopy(MAGIC, 0, output, 0, MAGIC.length);
        output[4] = VERSION;
        output[5] = FAMILY;
        output[6] = (byte) tag;
        System.arraycopy(payload, 0, output, HEADER, payload.length);
        return output;
    }

    public static Value decode(byte[] wire) {
        Objects.requireNonNull(wire, "ADT wire");
        if (wire.length < HEADER || wire.length > MAX_WIRE_BYTES
                || !Arrays.equals(Arrays.copyOf(wire, 4), MAGIC)
                || Byte.toUnsignedInt(wire[4]) != VERSION
                || Byte.toUnsignedInt(wire[5]) != FAMILY)
            throw new IllegalArgumentException("invalid ADT magic, version, family or size");
        int tag = Byte.toUnsignedInt(wire[6]);
        if (tag == 0) {
            if (wire.length != HEADER) throw new IllegalArgumentException("None contains data");
            return new None();
        }
        if (tag == 1) {
            if (wire.length != HEADER + 8) throw new IllegalArgumentException("Ok payload length");
            long value = 0;
            for (int i = 0; i < 8; ++i)
                value |= (long) Byte.toUnsignedInt(wire[HEADER + i]) << (i * 8);
            return new SomeOk(value);
        }
        if (tag == 2) {
            if (wire.length < HEADER + 2) throw new IllegalArgumentException("Err payload length");
            int length = Byte.toUnsignedInt(wire[HEADER])
                    | (Byte.toUnsignedInt(wire[HEADER + 1]) << 8);
            if (length > MAX_ERROR_BYTES || wire.length != HEADER + 2 + length)
                throw new IllegalArgumentException("Err payload length");
            try {
                String message = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(wire, HEADER + 2, length))
                        .toString();
                return new SomeErr(message);
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("invalid ADT error UTF-8", malformed);
            }
        }
        // Including CONTROL-shaped tags: no in-band DATA bytes mint authority.
        throw new IllegalArgumentException("unknown ADT variant / forbidden CONTROL tag");
    }
}
