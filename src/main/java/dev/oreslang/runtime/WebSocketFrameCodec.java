package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Strict bounded RFC 6455 frame codec used by the future actor-owned WebSocket transport.
 *
 * <p>This class deliberately owns no socket, actor, scheduler, or host object.
 * It converts one already-bounded client frame into immutable bytes and provides
 * a bounded fragmentation assembler. Network admission and actor ownership are
 * separate layers.</p>
 */
public final class WebSocketFrameCodec {
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;
    public static final int MAX_CONFIGURED_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private WebSocketFrameCodec() { }

    public record Frame(boolean fin, int opcode, byte[] payload) {
        public Frame {
            if (payload == null) {
                throw new IllegalArgumentException("WebSocket payload is required");
            }
            requireOpcode(opcode);
            validateFrameShape(
                    fin, opcode, payload.length, MAX_CONFIGURED_PAYLOAD_BYTES);
            validateTerminalPayload(fin, opcode, payload);
            payload = Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        byte[] payloadUnsafe() {
            return payload;
        }

        public boolean control() {
            return opcode >= 0x8;
        }
    }

    public record Message(int opcode, byte[] payload) {
        public Message {
            if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
                throw new IllegalArgumentException("WebSocket message opcode must be text or binary");
            }
            if (payload == null) {
                throw new IllegalArgumentException("WebSocket message payload is required");
            }
            if (opcode == OPCODE_TEXT) {
                validateUtf8(payload, "WebSocket text message");
            }
            payload = Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        /** Observe message length without allocating a second defensive payload copy. */
        public int payloadLength() {
            return payload.length;
        }
    }

    /**
     * Incrementally read exactly one client frame from a stream. Header and
     * extended length are validated before payload allocation, so a peer cannot
     * force allocation above maxFrameBytes merely by advertising a huge length.
     */
    public static Frame readClientFrame(InputStream input, int maxFrameBytes)
            throws IOException {
        if (input == null) throw new IllegalArgumentException("WebSocket input is required");
        requirePayloadLimit(maxFrameBytes, "maxFrameBytes");

        int first = readRequiredByte(input, "WebSocket frame first byte");
        int second = readRequiredByte(input, "WebSocket frame second byte");
        boolean fin = (first & 0x80) != 0;
        int rsv = first & 0x70;
        int opcode = first & 0x0f;
        requireOpcode(opcode);
        if (rsv != 0) {
            throw new IllegalArgumentException(
                    "WebSocket RSV bits require a negotiated extension");
        }
        if ((second & 0x80) == 0) {
            throw new IllegalArgumentException(
                    "client WebSocket frames must be masked");
        }

        int marker = second & 0x7f;
        long payloadLength;
        if (marker <= 125) {
            payloadLength = marker;
        } else if (marker == 126) {
            int high = readRequiredByte(input, "16-bit WebSocket payload length");
            int low = readRequiredByte(input, "16-bit WebSocket payload length");
            payloadLength = ((long) high << 8) | low;
            if (payloadLength < 126) {
                throw new IllegalArgumentException(
                        "non-canonical 16-bit WebSocket payload length");
            }
        } else {
            int high = readRequiredByte(input, "64-bit WebSocket payload length");
            if ((high & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "WebSocket payload length exceeds signed 63-bit range");
            }
            payloadLength = high;
            for (int i = 1; i < 8; i++) {
                payloadLength = (payloadLength << 8)
                        | readRequiredByte(input, "64-bit WebSocket payload length");
            }
            if (payloadLength <= 0xffffL) {
                throw new IllegalArgumentException(
                        "non-canonical 64-bit WebSocket payload length");
            }
        }

        validateFrameShape(fin, opcode, payloadLength, maxFrameBytes);
        if (payloadLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "WebSocket frame payload exceeds JVM array bound");
        }

        byte[] mask = readExactly(input, 4, "WebSocket masking key");
        byte[] payload = readExactly(
                input, (int) payloadLength, "WebSocket frame payload");
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        validateTerminalPayload(fin, opcode, payload);
        return new Frame(fin, opcode, payload);
    }

    /** Write one complete server frame without creating an intermediate wire array. */
    public static void writeServerFrame(
            OutputStream output,
            boolean fin,
            int opcode,
            byte[] payload,
            int maxFrameBytes) throws IOException {
        if (output == null) throw new IllegalArgumentException("WebSocket output is required");
        if (payload == null) throw new IllegalArgumentException("WebSocket payload is required");
        requirePayloadLimit(maxFrameBytes, "maxFrameBytes");
        requireOpcode(opcode);
        validateFrameShape(fin, opcode, payload.length, maxFrameBytes);
        validateTerminalPayload(fin, opcode, payload);

        output.write((fin ? 0x80 : 0) | opcode);
        if (payload.length <= 125) {
            output.write(payload.length);
        } else if (payload.length <= 0xffff) {
            output.write(126);
            output.write(payload.length >>> 8);
            output.write(payload.length);
        } else {
            output.write(127);
            long length = payload.length;
            for (int i = 0; i < 8; i++) {
                output.write((int) (length >>> (56 - (i * 8))) & 0xff);
            }
        }
        output.write(payload);
        output.flush();
    }

    /**
     * Decode exactly one client-to-server frame. Client frames must be masked,
     * RSV bits must be clear, lengths must be canonical, and the complete input
     * must contain exactly one frame with no trailing bytes.
     */
    public static Frame decodeClientFrame(byte[] wire, int maxFrameBytes) {
        if (wire == null) throw new IllegalArgumentException("WebSocket frame bytes are required");
        requirePayloadLimit(maxFrameBytes, "maxFrameBytes");
        if (wire.length < 2) throw new IllegalArgumentException("truncated WebSocket frame header");

        int first = Byte.toUnsignedInt(wire[0]);
        int second = Byte.toUnsignedInt(wire[1]);
        boolean fin = (first & 0x80) != 0;
        int rsv = first & 0x70;
        int opcode = first & 0x0f;
        requireOpcode(opcode);
        if (rsv != 0) throw new IllegalArgumentException("WebSocket RSV bits require a negotiated extension");

        boolean masked = (second & 0x80) != 0;
        if (!masked) throw new IllegalArgumentException("client WebSocket frames must be masked");

        int cursor = 2;
        int lengthMarker = second & 0x7f;
        long payloadLength;
        if (lengthMarker <= 125) {
            payloadLength = lengthMarker;
        } else if (lengthMarker == 126) {
            requireAvailable(wire, cursor, 2, "16-bit WebSocket payload length");
            payloadLength = ((long) Byte.toUnsignedInt(wire[cursor]) << 8)
                    | Byte.toUnsignedInt(wire[cursor + 1]);
            cursor += 2;
            if (payloadLength < 126) {
                throw new IllegalArgumentException("non-canonical 16-bit WebSocket payload length");
            }
        } else {
            requireAvailable(wire, cursor, 8, "64-bit WebSocket payload length");
            if ((wire[cursor] & 0x80) != 0) {
                throw new IllegalArgumentException("WebSocket payload length exceeds signed 63-bit range");
            }
            payloadLength = 0L;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | Byte.toUnsignedInt(wire[cursor + i]);
            }
            cursor += 8;
            if (payloadLength <= 0xffffL) {
                throw new IllegalArgumentException("non-canonical 64-bit WebSocket payload length");
            }
        }

        validateFrameShape(fin, opcode, payloadLength, maxFrameBytes);
        requireAvailable(wire, cursor, 4, "WebSocket masking key");
        byte[] mask = Arrays.copyOfRange(wire, cursor, cursor + 4);
        cursor += 4;

        if (payloadLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("WebSocket frame payload exceeds JVM array bound");
        }
        int payloadBytes = (int) payloadLength;
        if ((long) cursor + payloadBytes != wire.length) {
            if ((long) cursor + payloadBytes > wire.length) {
                throw new IllegalArgumentException("truncated WebSocket frame payload");
            }
            throw new IllegalArgumentException("trailing bytes after WebSocket frame");
        }

        byte[] payload = new byte[payloadBytes];
        for (int i = 0; i < payloadBytes; i++) {
            payload[i] = (byte) (wire[cursor + i] ^ mask[i & 3]);
        }

        validateTerminalPayload(fin, opcode, payload);
        return new Frame(fin, opcode, payload);
    }

    /** Encode one unmasked server-to-client frame using canonical length form. */
    public static byte[] encodeServerFrame(
            boolean fin, int opcode, byte[] payload, int maxFrameBytes) {
        if (payload == null) throw new IllegalArgumentException("WebSocket payload is required");
        requirePayloadLimit(maxFrameBytes, "maxFrameBytes");
        requireOpcode(opcode);
        validateFrameShape(fin, opcode, payload.length, maxFrameBytes);
        validateTerminalPayload(fin, opcode, payload);

        int headerBytes = payload.length <= 125 ? 2 : payload.length <= 0xffff ? 4 : 10;
        byte[] wire = new byte[Math.addExact(headerBytes, payload.length)];
        wire[0] = (byte) ((fin ? 0x80 : 0) | opcode);
        int cursor;
        if (payload.length <= 125) {
            wire[1] = (byte) payload.length;
            cursor = 2;
        } else if (payload.length <= 0xffff) {
            wire[1] = 126;
            wire[2] = (byte) (payload.length >>> 8);
            wire[3] = (byte) payload.length;
            cursor = 4;
        } else {
            wire[1] = 127;
            long length = payload.length;
            for (int i = 0; i < 8; i++) {
                wire[2 + i] = (byte) (length >>> (56 - (i * 8)));
            }
            cursor = 10;
        }
        System.arraycopy(payload, 0, wire, cursor, payload.length);
        return wire;
    }

    private static void validateFrameShape(
            boolean fin, int opcode, long payloadLength, int maxFrameBytes) {
        if (payloadLength < 0 || payloadLength > maxFrameBytes) {
            throw new IllegalArgumentException(
                    "WebSocket frame payload exceeds configured limit " + maxFrameBytes);
        }
        if (opcode >= 0x8) {
            if (!fin) throw new IllegalArgumentException("WebSocket control frames cannot be fragmented");
            if (payloadLength > 125) {
                throw new IllegalArgumentException("WebSocket control payload exceeds 125 bytes");
            }
        }
    }

    private static void validateTerminalPayload(boolean fin, int opcode, byte[] payload) {
        if (opcode == OPCODE_CLOSE) validateClosePayload(payload);
        if (opcode == OPCODE_TEXT && fin) validateUtf8(payload, "WebSocket text frame");
    }

    private static void validateClosePayload(byte[] payload) {
        if (payload.length == 1) {
            throw new IllegalArgumentException("WebSocket close payload cannot contain one byte");
        }
        if (payload.length < 2) return;

        int code = (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
        boolean standard = code >= 1000 && code <= 1014
                && code != 1004 && code != 1005 && code != 1006;
        boolean application = code >= 3000 && code <= 4999;
        if (!standard && !application) {
            throw new IllegalArgumentException("invalid WebSocket close status code " + code);
        }

        if (payload.length > 2) {
            validateUtf8(Arrays.copyOfRange(payload, 2, payload.length), "WebSocket close reason");
        }
    }

    private static void validateUtf8(byte[] payload, String what) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(what + " must be valid UTF-8", invalid);
        }
    }

    private static void requireOpcode(int opcode) {
        if (opcode != OPCODE_CONTINUATION
                && opcode != OPCODE_TEXT
                && opcode != OPCODE_BINARY
                && opcode != OPCODE_CLOSE
                && opcode != OPCODE_PING
                && opcode != OPCODE_PONG) {
            throw new IllegalArgumentException("unsupported or reserved WebSocket opcode " + opcode);
        }
    }

    private static void requirePayloadLimit(int limit, String name) {
        if (limit < 1 || limit > MAX_CONFIGURED_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    name + " must be in 1.." + MAX_CONFIGURED_PAYLOAD_BYTES);
        }
    }

    private static int readRequiredByte(InputStream input, String what)
            throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("truncated " + what);
        return value;
    }

    private static byte[] readExactly(InputStream input, int length, String what)
            throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) throw new EOFException("truncated " + what);
            if (read == 0) {
                int one = input.read();
                if (one < 0) throw new EOFException("truncated " + what);
                bytes[offset++] = (byte) one;
                continue;
            }
            offset += read;
        }
        return bytes;
    }

    private static void requireAvailable(byte[] wire, int offset, int length, String what) {
        if (offset < 0 || length < 0 || offset > wire.length - length) {
            throw new IllegalArgumentException("truncated " + what);
        }
    }

    /**
     * Bounded data-message assembler. Control frames are intentionally returned
     * to the transport separately and never consume message capacity.
     */
    public static final class MessageAssembler {
        private final int maxMessageBytes;
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        private int activeOpcode = -1;

        public MessageAssembler(int maxMessageBytes) {
            requirePayloadLimit(maxMessageBytes, "maxMessageBytes");
            this.maxMessageBytes = maxMessageBytes;
        }

        public Optional<Message> accept(Frame frame) {
            if (frame == null) throw new IllegalArgumentException("WebSocket frame is required");
            if (frame.control()) return Optional.empty();

            int opcode = frame.opcode();
            byte[] payload = frame.payloadUnsafe();

            if (opcode == OPCODE_CONTINUATION) {
                if (activeOpcode == -1) {
                    throw new IllegalArgumentException("WebSocket continuation without fragmented message");
                }
                append(payload);
                if (!frame.fin()) return Optional.empty();

                byte[] complete = fragments.toByteArray();
                int completedOpcode = activeOpcode;
                reset();
                if (completedOpcode == OPCODE_TEXT) {
                    validateUtf8(complete, "fragmented WebSocket text message");
                }
                return Optional.of(new Message(completedOpcode, complete));
            }

            if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
                throw new IllegalArgumentException("unexpected WebSocket data opcode " + opcode);
            }
            if (activeOpcode != -1) {
                throw new IllegalArgumentException(
                        "new WebSocket data frame before fragmented message completed");
            }

            if (frame.fin()) {
                if (payload.length > maxMessageBytes) {
                    throw new IllegalArgumentException(
                            "WebSocket message exceeds configured limit " + maxMessageBytes);
                }
                if (opcode == OPCODE_TEXT) {
                    validateUtf8(payload, "WebSocket text message");
                }
                return Optional.of(new Message(opcode, payload));
            }

            activeOpcode = opcode;
            fragments.reset();
            append(payload);
            return Optional.empty();
        }

        public boolean fragmentedMessagePending() {
            return activeOpcode != -1;
        }

        public void reset() {
            activeOpcode = -1;
            fragments.reset();
        }

        private void append(byte[] payload) {
            if (payload.length > maxMessageBytes - fragments.size()) {
                reset();
                throw new IllegalArgumentException(
                        "WebSocket message exceeds configured limit " + maxMessageBytes);
            }
            fragments.writeBytes(payload);
        }
    }
}
