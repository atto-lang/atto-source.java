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
    record BinaryParsed(String opcode, List<OresBytes> bulk, int consumed) { }

    private record DecodedFrame(String opcode, List<byte[]> bulk, int consumed) { }

    /**
     * Binary-safe codec path: no intermediate hex strings and no boxed
     * List<int>. Each bulk payload is copied once from the network read
     * buffer into detached immutable OresBytes storage. Consumers must take
     * or close every returned OresBytes handle.
     */
    static BinaryParsed parseBinary(ByteBuffer bytes) throws IOException {
        DecodedFrame frame = decode(bytes);
        if (frame == null) return null;
        ArrayList<OresBytes> bulk = new ArrayList<>(frame.bulk().size());
        for (byte[] payload : frame.bulk()) bulk.add(OresBytes.fromDetached(payload));
        return new BinaryParsed(frame.opcode(), List.copyOf(bulk), frame.consumed());
    }

    /** Existing public guest wire contract; retains legacy hex compatibility. */
    static Parsed parse(ByteBuffer bytes) throws IOException {
        DecodedFrame frame = decode(bytes);
        if (frame == null) return null;
        ArrayList<String> result = new ArrayList<>(frame.bulk().size() + 1);
        result.add(frame.opcode());
        for (byte[] payload : frame.bulk()) result.add(HEX.formatHex(payload));
        return new Parsed(List.copyOf(result), frame.consumed());
    }

    /** One validation engine used by both the legacy and binary result paths. */
    private static DecodedFrame decode(ByteBuffer bytes) throws IOException {
        int start = bytes.position();
        int pos = start;
        int line = endOfLine(bytes, pos);
        if (line < 0) return null;
        if (bytes.get(pos) != '*') throw new IOException("expected RESP2 array");
        int argc = positiveDecimal(bytes, pos + 1, line, MAX_ARGS);
        if (argc < 1) throw new IOException("empty RESP2 array");
        pos = line + 2;
        String opcode = null;
        ArrayList<byte[]> args = new ArrayList<>(Math.max(0, argc - 1));
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
            byte[] payload = new byte[len];
            ByteBuffer source = bytes.duplicate();
            source.position(pos);
            source.get(payload);
            if (arg == 0) {
                for (byte item : payload) {
                    if (item < 33 || item > 126) {
                        throw new IOException("RESP opcode must be ASCII");
                    }
                }
                opcode = new String(payload, StandardCharsets.US_ASCII)
                        .toUpperCase(Locale.ROOT);
            } else {
                args.add(payload);
            }
            pos += len + 2;
        }
        return new DecodedFrame(opcode, List.copyOf(args), pos);
    }

    /** Encode an owned binary value directly into a RESP frame, without hex. */
    static byte[] encodeBinary(String status, OresBytes payload) throws IOException {
        if (!"VALUE".equals(status)) return encode(status, "");
        if (payload == null) throw new IOException("VALUE needs owned byte payload");
        try (OresBytes.ReadLease lease = payload.readLease()) {
            ByteBuffer value = lease.buffer();
            int n = value.remaining();
            if (n > MAX_VALUE) throw new IOException("RESP value exceeds cap");
            byte[] header = ("$" + n + "\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] frame = new byte[header.length + n + 2];
            System.arraycopy(header, 0, frame, 0, header.length);
            value.get(frame, header.length, n);
            frame[frame.length - 2] = '\r';
            frame[frame.length - 1] = '\n';
            return frame;
        }
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
