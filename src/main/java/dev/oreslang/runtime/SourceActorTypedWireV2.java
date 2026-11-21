package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Closed-world compiler-ABI-bound primitive actor-mail frame. Only a trusted
 * host may bind this to an actual compiled ActorMail<int|bool|string> receiver.
 *
 * <p>Canonical ORM2 header: 4 magic, 1 version, 1 primitive kind, 2 zero
 * flags, 16 logical UUID, 8 incarnation, 32 compiler ABI SHA-256, 4 payload
 * length, followed by exactly N bytes. Never carries a mutable guest graph,
 * type-erased capability, cross-process credential or route-epoch authority.</p>
 */
final class SourceActorTypedWireV2 {
    static final int HEADER_BYTES = 68;
    static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int MAGIC = 0x4f524d32; // "ORM2"
    private static final int VERSION = 2;

    record Binding(
            SourceActorScalarWireV1.Binding identity,
            ActorRuntime.ProxyWireScalarKind kind) {
        Binding {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private SourceActorTypedWireV2() { }

    private static int nibble(char c) {
        return c <= '9' ? c - '0' : c - 'a' + 10;
    }

    private static int maximum(int configured) {
        if (configured < HEADER_BYTES + 1)
            throw new IllegalArgumentException("typed actor mail byte capacity too small");
        return Math.min(configured, MAX_FRAME_BYTES);
    }

    /**
     * Preflight strict UTF-16 before any payload buffer allocation. The exact
     * UTF-8 length is always >= the UTF-16 code-unit count for well-formed
     * strings, but may be much larger for multibyte values. Rejecting from
     * the source String directly prevents allocate-then-reject pressure at
     * a configured quota and rejects unpaired surrogate code units.
     */
    private static int checkedUtf8Length(String value, int available) {
        if (value.length() > available)
            throw new IllegalArgumentException(
                    "typed actor string exceeds bounded frame capacity");
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int width;
            if (ch <= 0x7f) {
                width = 1;
            } else if (ch <= 0x7ff) {
                width = 2;
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "unpaired high surrogate in actor string");
                }
                i++;
                width = 4;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(
                        "unpaired low surrogate in actor string");
            } else {
                width = 3;
            }
            // Subtract rather than add before bounds checking: count never
            // exceeds the configured <= 1 MiB limit and cannot overflow.
            if (width > available - count)
                throw new IllegalArgumentException(
                        "typed actor UTF-8 exceeds bounded frame capacity");
            count += width;
        }
        return count;
    }

    private static byte[] frame(
            Binding binding, byte[] payload, int configuredLimit) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(payload, "payload");
        int max = maximum(configuredLimit);
        if (payload.length > max - HEADER_BYTES)
            throw new IllegalArgumentException("typed actor mail exceeds bounded frame capacity");
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC).put((byte) VERSION).put((byte) binding.kind().tag());
        out.putShort((short) 0);
        out.putLong(binding.identity().logicalActorId().getMostSignificantBits());
        out.putLong(binding.identity().logicalActorId().getLeastSignificantBits());
        out.putLong(binding.identity().incarnation());
        String digest = binding.identity().messageAbiSha256();
        for (int i = 0; i < 32; ++i) {
            int j = i * 2;
            out.put((byte) ((nibble(digest.charAt(j)) << 4)
                    | nibble(digest.charAt(j + 1))));
        }
        out.putInt(payload.length);
        out.put(payload);
        return out.array();
    }

    static byte[] encodeLong(Binding binding, long value, int maxBytes) {
        if (binding.kind() != ActorRuntime.ProxyWireScalarKind.INT)
            throw new SecurityException("compiled receive type does not accept int");
        byte[] payload = ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).putLong(value).array();
        return frame(binding, payload, maxBytes);
    }

    static byte[] encodeBoolean(Binding binding, boolean value, int maxBytes) {
        if (binding.kind() != ActorRuntime.ProxyWireScalarKind.BOOL)
            throw new SecurityException("compiled receive type does not accept bool");
        return frame(binding, new byte[] {(byte) (value ? 1 : 0)}, maxBytes);
    }

    static byte[] encodeString(Binding binding, String value, int maxBytes) {
        if (binding.kind() != ActorRuntime.ProxyWireScalarKind.STRING)
            throw new SecurityException("compiled receive type does not accept string");
        Objects.requireNonNull(value, "value");
        int available = maximum(maxBytes) - HEADER_BYTES;
        int exactUtf8Length = checkedUtf8Length(value, available);
        ByteBuffer utf8 = ByteBuffer.allocate(exactUtf8Length);
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            var result = encoder.encode(CharBuffer.wrap(value), utf8, true);
            if (result.isOverflow())
                throw new IllegalArgumentException("typed actor UTF-8 exceeds bounded capacity");
            if (result.isError()) result.throwException();
            result = encoder.flush(utf8);
            if (result.isOverflow())
                throw new IllegalArgumentException("typed actor UTF-8 exceeds bounded capacity");
            if (result.isError()) result.throwException();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("noncanonical UTF-16 actor string", invalid);
        }
        if (utf8.position() != exactUtf8Length)
            throw new IllegalStateException(
                    "typed actor UTF-8 encoder violated checked byte count");
        return frame(binding, utf8.array(), maxBytes);
    }

    static Object decode(Binding binding, byte[] wire) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(wire, "wire");
        if (wire.length < HEADER_BYTES || wire.length > MAX_FRAME_BYTES)
            throw new SecurityException("invalid bounded typed actor frame length");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (in.getInt() != MAGIC || (in.get() & 0xff) != VERSION
                || (in.get() & 0xff) != binding.kind().tag()
                || in.getShort() != 0)
            throw new SecurityException("wrong typed actor frame version/kind/flags");
        long mismatch = in.getLong()
                ^ binding.identity().logicalActorId().getMostSignificantBits();
        mismatch |= in.getLong()
                ^ binding.identity().logicalActorId().getLeastSignificantBits();
        mismatch |= in.getLong() ^ binding.identity().incarnation();
        String digest = binding.identity().messageAbiSha256();
        for (int i = 0; i < 32; ++i) {
            int j = i * 2;
            int expected = (nibble(digest.charAt(j)) << 4)
                    | nibble(digest.charAt(j + 1));
            mismatch |= (in.get() & 0xff) ^ expected;
        }
        if (mismatch != 0)
            throw new SecurityException("foreign logical actor identity or compiler message ABI");
        int payloadLength = in.getInt();
        if (payloadLength < 0 || payloadLength != in.remaining())
            throw new SecurityException("noncanonical typed actor payload length");
        return switch (binding.kind()) {
            case INT -> {
                if (payloadLength != Long.BYTES)
                    throw new SecurityException("wrong typed actor int length");
                yield in.getLong();
            }
            case BOOL -> {
                if (payloadLength != 1)
                    throw new SecurityException("wrong typed actor bool length");
                int octet = in.get() & 0xff;
                if (octet > 1)
                    throw new SecurityException("noncanonical typed actor boolean");
                yield octet == 1;
            }
            case STRING -> {
                try {
                    yield StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(in).toString();
                } catch (CharacterCodingException invalid) {
                    throw new SecurityException("invalid typed actor UTF-8", invalid);
                }
            }
        };
    }
}
