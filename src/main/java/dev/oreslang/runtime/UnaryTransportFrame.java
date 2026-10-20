package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Strict, transport-owned v1 unary RPC envelope for explicitly framed TCP
 * streams and already-assembled WebSocket binary messages. Neither a raw
 * TCP read nor a WebSocket connection is implicitly a unary request.
 *
 * This is a host framing primitive, not guest actor dispatch, TCP admission,
 * an RFC 6455 frame parser, or an HTTP response adapter.
 */
final class UnaryTransportFrame {
    static final int VERSION = 1;
    static final int ENVELOPE_BYTES = 26;
    static final int MAX_PAYLOAD_BYTES = 1 << 20;
    static final int MAX_ENVELOPE_BYTES = ENVELOPE_BYTES + MAX_PAYLOAD_BYTES;

    enum Transport { TCP, WEBSOCKET }
    enum Kind {
        REQUEST(1), REPLY(2), ERROR(3), CANCEL(4);
        final int wire;
        Kind(int wire) { this.wire = wire; }
        static Kind fromWire(int wire) throws IOException {
            for (Kind candidate : values()) if (candidate.wire == wire) return candidate;
            throw new IOException("unknown unary message kind");
        }
    }

    record Envelope(
            Kind kind,
            long sessionId,
            long generation,
            long correlationId,
            OresBytes payloadValue) {
        Envelope {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadValue, "payloadValue");
            if (sessionId <= 0 || generation <= 0 || correlationId <= 0)
                throw new IllegalArgumentException("session, generation and correlation ID must be positive");
            if (payloadValue.size() > MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("unary payload exceeds configured limit");
            if (kind == Kind.CANCEL && !payloadValue.isEmpty())
                throw new IllegalArgumentException("cancel envelopes cannot carry payload bytes");
        }

        Envelope(
                Kind kind,
                long sessionId,
                long generation,
                long correlationId,
                byte[] payload) {
            this(kind, sessionId, generation, correlationId, OresBytes.copyOf(payload));
        }

        static Envelope fromFreshPayload(
                Kind kind,
                long sessionId,
                long generation,
                long correlationId,
                byte[] freshPayload) {
            Objects.requireNonNull(freshPayload, "freshPayload");
            return new Envelope(
                    kind, sessionId, generation, correlationId,
                    OresBytes.wrapTrusted(freshPayload));
        }

        byte[] payload() { return payloadValue.toByteArray(); }
        int payloadLength() { return payloadValue.size(); }
    }

    private UnaryTransportFrame() { }

    private static byte[] body(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        OresBytes payload = envelope.payloadValue;
        ByteBuffer out = ByteBuffer.allocate(ENVELOPE_BYTES + payload.size()).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) VERSION).put((byte) envelope.kind().wire)
                .putLong(envelope.sessionId()).putLong(envelope.generation())
                .putLong(envelope.correlationId());
        payload.putInto(out);
        return out.array();
    }

    private static Envelope parse(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < ENVELOPE_BYTES || bytes.length > MAX_ENVELOPE_BYTES)
            throw new IOException("invalid unary envelope size");
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (Byte.toUnsignedInt(in.get()) != VERSION)
            throw new IOException("unsupported unary envelope version");
        Kind kind = Kind.fromWire(Byte.toUnsignedInt(in.get()));
        long sessionId = in.getLong(), generation = in.getLong(), correlationId = in.getLong();
        if (sessionId <= 0 || generation <= 0 || correlationId <= 0)
            throw new IOException("invalid unary session, generation or correlation ID");
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        if (kind == Kind.CANCEL && payload.length != 0)
            throw new IOException("cancel envelope contains payload");
        return Envelope.fromFreshPayload(kind, sessionId, generation, correlationId, payload);
    }

    /** One WebSocket binary MESSAGE, after RFC 6455 reassembly/validation. */
    static byte[] encodeWebSocketBinary(Envelope envelope) { return body(envelope); }
    static Envelope decodeWebSocketBinary(byte[] message) throws IOException {
        return decodeWebSocketBinary(message, MAX_PAYLOAD_BYTES);
    }
    static Envelope decodeWebSocketBinary(byte[] message, int maxPayloadBytes) throws IOException {
        checkBudget(maxPayloadBytes);
        if (message == null || message.length > ENVELOPE_BYTES + maxPayloadBytes)
            throw new IOException("WebSocket unary message exceeds request byte limit");
        return parse(message);
    }

    private static void checkBudget(int maxPayloadBytes) {
        if (maxPayloadBytes < 0 || maxPayloadBytes > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("invalid unary request byte limit");
    }

    /** TCP framing: 4-byte big-endian envelope byte count, then that envelope. */
    static byte[] encodeTcp(Envelope envelope) {
        byte[] bytes = body(envelope);
        return ByteBuffer.allocate(4 + bytes.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.length).put(bytes).array();
    }

    static void writeTcp(OutputStream output, Envelope envelope) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(envelope, "envelope");
        java.io.DataOutputStream writer = new java.io.DataOutputStream(output);
        writer.writeInt(ENVELOPE_BYTES + envelope.payloadValue.size());
        writer.writeByte(VERSION);
        writer.writeByte(envelope.kind().wire);
        writer.writeLong(envelope.sessionId());
        writer.writeLong(envelope.generation());
        writer.writeLong(envelope.correlationId());
        envelope.payloadValue.writeTo(writer);
    }

    /**
     * Parsed TCP envelope metadata. Reading this object consumes only the
     * 4-byte frame length and fixed 26-byte envelope header, never the payload.
     * Callers can therefore reserve count/byte credits before payload allocation.
     */
    record TcpHead(Kind kind, long sessionId, long generation,
                   long correlationId, int payloadBytes) {
        TcpHead {
            Objects.requireNonNull(kind, "kind");
            if (sessionId <= 0 || generation <= 0 || correlationId <= 0 || payloadBytes < 0)
                throw new IllegalArgumentException("invalid unary TCP header");
            if (kind == Kind.CANCEL && payloadBytes != 0)
                throw new IllegalArgumentException("cancel envelopes cannot carry payload bytes");
        }
    }

    static Envelope readTcp(InputStream input) throws IOException {
        return readTcp(input, MAX_PAYLOAD_BYTES);
    }

    /**
     * Reads one TCP unary frame with a peer/session payload budget.
     * The length and fixed header are checked before payload allocation.
     */
    static Envelope readTcp(InputStream input, int maxPayloadBytes) throws IOException {
        TcpHead head = readTcpHead(input, maxPayloadBytes);
        return readTcpPayload(input, head);
    }

    /**
     * Read only the prefix + fixed unary header. This is the admission boundary
     * used by session adapters before allocating or reading request payloads.
     */
    static TcpHead readTcpHead(InputStream input, int maxPayloadBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        checkBudget(maxPayloadBytes);
        DataInputStream reader = new DataInputStream(input);
        int length = reader.readInt(); // EOF is a transport terminal, not an empty request.
        if (length < ENVELOPE_BYTES || length > ENVELOPE_BYTES + maxPayloadBytes)
            throw new IOException("TCP unary frame exceeds request byte limit");

        byte[] header = new byte[ENVELOPE_BYTES];
        try { reader.readFully(header); }
        catch (EOFException incomplete) { throw new EOFException("truncated TCP unary frame header"); }

        ByteBuffer in = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (Byte.toUnsignedInt(in.get()) != VERSION)
            throw new IOException("unsupported unary envelope version");
        Kind kind = Kind.fromWire(Byte.toUnsignedInt(in.get()));
        long sessionId = in.getLong(), generation = in.getLong(), correlationId = in.getLong();
        if (sessionId <= 0 || generation <= 0 || correlationId <= 0)
            throw new IOException("invalid unary session, generation or correlation ID");

        int payloadBytes = length - ENVELOPE_BYTES;
        if (kind == Kind.CANCEL && payloadBytes != 0)
            throw new IOException("cancel envelope contains payload");
        return new TcpHead(kind, sessionId, generation, correlationId, payloadBytes);
    }

    /**
     * Read the payload for a previously validated/admitted TCP header.
     * The returned array is a fresh allocation owned by the caller.
     */
    static byte[] readTcpPayloadBytes(InputStream input, TcpHead head) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(head, "head");
        byte[] payload = new byte[head.payloadBytes()];
        try { new DataInputStream(input).readFully(payload); }
        catch (EOFException incomplete) { throw new EOFException("truncated TCP unary frame payload"); }
        return payload;
    }

    /** Defensive envelope API layered on the owned-payload reader. */
    static Envelope readTcpPayload(InputStream input, TcpHead head) throws IOException {
        byte[] payload = readTcpPayloadBytes(input, head);
        return new Envelope(head.kind(), head.sessionId(), head.generation(),
                head.correlationId(), payload);
    }

    /**
     * Drain a rejected but syntactically valid payload without allocating it.
     * This preserves framing after a bounded-admission rejection.
     */
    static void discardTcpPayload(InputStream input, TcpHead head) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(head, "head");
        DataInputStream reader = new DataInputStream(input);
        int remaining = head.payloadBytes();
        byte[] scratch = new byte[Math.min(8192, Math.max(1, remaining))];
        while (remaining > 0) {
            int read = reader.read(scratch, 0, Math.min(scratch.length, remaining));
            if (read < 0) throw new EOFException("truncated rejected TCP unary frame payload");
            if (read == 0) continue;
            remaining -= read;
        }
    }
}
