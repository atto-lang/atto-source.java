package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Bounded incremental RESP2 framing for async NIO sockets.
 *
 * This is a transitional backend codec: the caller owns all mutable KV state
 * in an Atto actor, and every physical read/write is completed by NIO2.
 * The returned key/value arguments are exact lowercase ASCII hex; a future
 * core Bytes type should replace this allocation-heavy compatibility form.
 */
final class AsyncNioResp2 {
    static final int MAX_ARGS = 3;
    static final int MAX_KEY = 4096;
    static final int MAX_VALUE = 1024 * 1024;
    static final int MAX_FRAME = MAX_VALUE + MAX_KEY + 1024;
    static final int MAX_HEADER = 64;
    private static final HexFormat HEX = HexFormat.of();

    record Parsed(List<String> parts, int consumed) { }

    private AsyncNioResp2() { }

    private static int endOfLine(ByteBuffer bytes, int begin) throws IOException {
        if (begin >= bytes.limit()) return -1;
        int bound = Math.min(bytes.limit(), begin + MAX_HEADER);
        for (int i = begin; i < bound; i++) {
            if (bytes.get(i) != '\n') continue;
            if (i == begin || bytes.get(i - 1) != '\r') {
                throw new IOException("RESP header requires CRLF");
            }
            return i - 1;
        }
        if (bytes.limit() - begin >= MAX_HEADER) {
            throw new IOException("RESP header exceeds cap");
        }
        return -1;
    }

    private static int positiveDecimal(ByteBuffer bytes, int first, int end, int max)
            throws IOException {
        if (first >= end) throw new IOException("empty RESP integer");
        int n = 0;
        for (int i = first; i < end; i++) {
            int digit = (bytes.get(i) & 0xff) - '0';
            if (digit < 0 || digit > 9 || digit > max || n > (max - digit) / 10) {
                throw new IOException("invalid or excessive RESP decimal");
            }
            n = n * 10 + digit;
        }
        return n;
    }

    /**
     * Parse one complete RESP2 array from source.position() to limit().
     * Returns null if bytes are incomplete. On success, consumed is the
     * absolute buffer offset immediately after the frame (pipeline-safe).
     * Incomplete frames must not move the buffer position or consume data.
     */
    static Parsed parse(ByteBuffer bytes) throws IOException {
        int start = bytes.position();
        int pos = start;
        int line = endOfLine(bytes, pos);
        if (line < 0) return null;
        if (bytes.get(pos) != '*') throw new IOException("expected RESP2 array");
        int argc = positiveDecimal(bytes, pos + 1, line, MAX_ARGS);
        if (argc < 1) throw new IOException("empty RESP2 array");
        pos = line + 2;
        String opcode = null;
        ArrayList<String> args = new ArrayList<>(argc);
        for (int arg = 0; arg < argc; arg++) {
            line = endOfLine(bytes, pos);
            if (line < 0) return null;
            if (bytes.get(pos) != '$') throw new IOException("expected RESP2 bulk");
            int max = arg == 0 ? 16
                    : (arg == 1 && ("SET".equals(opcode) || "GET".equals(opcode)
                        || "DEL".equals(opcode))) ? MAX_KEY : MAX_VALUE;
            int len = positiveDecimal(bytes, pos + 1, line, max);
            if (arg == 0 && len == 0) throw new IOException("empty RESP opcode");
            pos = line + 2;
            if ((long) pos - start + len + 2 > MAX_FRAME) {
                throw new IOException("RESP frame exceeds bound");
            }
            if (bytes.limit() - pos < len + 2) return null;
            if (bytes.get(pos + len) != '\r' || bytes.get(pos + len + 1) != '\n') {
                throw new IOException("bad RESP bulk trailer");
            }
            if (arg == 0) {
                byte[] raw = new byte[len];
                for (int n = 0; n < len; n++) {
                    byte item = bytes.get(pos + n);
                    if (item < 33 || item > 126) {
                        throw new IOException("RESP opcode must be ASCII");
                    }
                    raw[n] = item;
                }
                opcode = new String(raw, StandardCharsets.US_ASCII)
                        .toUpperCase(Locale.ROOT);
                args.add(opcode);
            } else {
                byte[] payload = new byte[len];
                ByteBuffer source = bytes.duplicate();
                source.position(pos);
                source.get(payload);
                args.add(HEX.formatHex(payload));
            }
            pos += len + 2;
        }
        return new Parsed(List.copyOf(args), pos);
    }


    // One actor-output reply per command. A VALUE reply carries lowercase
    // hex data after a reserved delimiter, not raw binary or arbitrary text.
    // Bounds are checked before substring/hex decoding or frame allocation.
    static byte[] encodeCompositeReply(String reply) throws IOException {
        if (reply == null) throw new IOException("missing reply");
        if (reply.startsWith("VALUE:")) {
            if (reply.length() > MAX_VALUE * 2 + 6) {
                throw new IOException("RESP reply payload exceeds bound");
            }
            return encode("VALUE", reply.substring(6));
        }
        return switch (reply) {
            case "OK", "PONG", "NIL", "ONE", "ZERO", "ERR" ->
                    encode(reply, "");
            default -> throw new IOException("invalid actor reply envelope");
        };
    }

    /** Binary RESP2 frame; argument arrays are immutable owned Bytes, not hex. */
    record BinaryParsed(String opcode, List<OresBytes> arguments, int consumed) { }
    private record BinaryRange(int offset, int size) { }

    /**
     * Parse RESP2 on a reused NIO receive buffer without changing position.
     * Defer key/value array copies until the entire frame validates.
     */
    static BinaryParsed parseBinary(ByteBuffer bytes) throws IOException {
        int start = bytes.position(), pos = start;
        int line = endOfLine(bytes, pos);
        if (line < 0) return null;
        if (bytes.get(pos) != '*') throw new IOException("expected RESP2 array");
        int argc = positiveDecimal(bytes, pos + 1, line, MAX_ARGS);
        if (argc < 1) throw new IOException("empty RESP2 array");
        pos = line + 2;
        String opcode = null;
        ArrayList<BinaryRange> ranges = new ArrayList<>(Math.max(0, argc - 1));
        for (int arg = 0; arg < argc; arg++) {
            line = endOfLine(bytes, pos);
            if (line < 0) return null;
            if (bytes.get(pos) != '$') throw new IOException("expected RESP2 bulk");
            int max = arg == 0 ? 16
                    : (arg == 1 && ("SET".equals(opcode) || "GET".equals(opcode)
                        || "DEL".equals(opcode))) ? MAX_KEY : MAX_VALUE;
            int len = positiveDecimal(bytes, pos + 1, line, max);
            if (arg == 0 && len == 0) throw new IOException("empty RESP opcode");
            pos = line + 2;
            if ((long) pos - start + len + 2 > MAX_FRAME) {
                throw new IOException("RESP frame exceeds bound");
            }
            if (bytes.limit() - pos < len + 2) return null;
            if (bytes.get(pos + len) != '\r' || bytes.get(pos + len + 1) != '\n') {
                throw new IOException("bad RESP bulk trailer");
            }
            if (arg == 0) {
                byte[] raw = new byte[len];
                for (int n = 0; n < len; n++) {
                    byte item = bytes.get(pos + n);
                    if (item < 33 || item > 126) {
                        throw new IOException("RESP opcode must be ASCII");
                    }
                    raw[n] = item;
                }
                opcode = new String(raw, StandardCharsets.US_ASCII)
                        .toUpperCase(Locale.ROOT);
            } else {
                ranges.add(new BinaryRange(pos, len));
            }
            pos += len + 2;
        }
        ArrayList<OresBytes> args = new ArrayList<>(ranges.size());
        for (BinaryRange range : ranges) {
            byte[] owned = new byte[range.size()];
            ByteBuffer view = bytes.duplicate();
            view.position(range.offset());
            view.get(owned);
            args.add(OresBytes.fromParserOwned(owned));
        }
        return new BinaryParsed(opcode, List.copyOf(args), pos);
    }

    /** Encode binary RESP2 in one detached response frame allocation. */
    static byte[] encodeBinary(String status, OresBytes payload) throws IOException {
        if (!"VALUE".equals(status)) {
            if (payload != null && payload.size() != 0) {
                throw new IOException("non-VALUE reply cannot contain binary data");
            }
            return encode(status, "");
        }
        if (payload == null) throw new IOException("missing binary reply value");
        if (payload.size() > MAX_VALUE) throw new IOException("RESP value exceeds bound");
        byte[] head = ("$" + payload.size() + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[head.length + payload.size() + 2];
        System.arraycopy(head, 0, result, 0, head.length);
        payload.copyInto(result, head.length);
        result[result.length - 2] = '\r';
        result[result.length - 1] = '\n';
        return result;
    }

    static byte[] encode(String status, String hexPayload) throws IOException {
        if (status == null) throw new IOException("missing RESP status");
        if (hexPayload == null) hexPayload = "";
        return switch (status) {
            case "OK" -> "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
            case "PONG" -> "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
            case "NIL" -> "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
            case "ONE" -> ":1\r\n".getBytes(StandardCharsets.US_ASCII);
            case "ZERO" -> ":0\r\n".getBytes(StandardCharsets.US_ASCII);
            case "ERR" -> "-ERR unsupported command\r\n".getBytes(StandardCharsets.US_ASCII);
            case "VALUE" -> {
                if ((hexPayload.length() & 1) != 0 || hexPayload.length() > MAX_VALUE * 2) {
                    throw new IOException("invalid value hex size");
                }
                byte[] raw;
                try {
                    raw = HEX.parseHex(hexPayload);
                } catch (IllegalArgumentException badHex) {
                    throw new IOException("invalid value hex", badHex);
                }
                byte[] head = ("$" + raw.length + "\r\n").getBytes(StandardCharsets.US_ASCII);
                byte[] frame = new byte[head.length + raw.length + 2];
                System.arraycopy(head, 0, frame, 0, head.length);
                System.arraycopy(raw, 0, frame, head.length, raw.length);
                frame[frame.length - 2] = '\r';
                frame[frame.length - 1] = '\n';
                yield frame;
            }
            default -> throw new IOException("unsupported RESP reply status");
        };
    }
}
