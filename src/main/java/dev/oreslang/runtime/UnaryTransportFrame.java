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
    static final int ENVELOPE_BYTES = 18;
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

    record Envelope(Kind kind, long generation, long correlationId, byte[] payload) {
        Envelope {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
            if (generation <= 0 || correlationId <= 0)
                throw new IllegalArgumentException("generation and correlation ID must be positive");
            if (payload.length > MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("unary payload exceeds configured limit");
            if (kind == Kind.CANCEL && payload.length != 0)
                throw new IllegalArgumentException("cancel envelopes cannot carry payload bytes");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private UnaryTransportFrame() { }

    private static byte[] body(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        byte[] payload = envelope.payload();
        ByteBuffer out = ByteBuffer.allocate(ENVELOPE_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) VERSION).put((byte) envelope.kind().wire)
                .putLong(envelope.generation()).putLong(envelope.correlationId()).put(payload);
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
        long generation = in.getLong(), correlationId = in.getLong();
        if (generation <= 0 || correlationId <= 0)
            throw new IOException("invalid unary generation or correlation ID");
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        if (kind == Kind.CANCEL && payload.length != 0)
            throw new IOException("cancel envelope contains payload");
        return new Envelope(kind, generation, correlationId, payload);
    }

    /** One WebSocket binary MESSAGE, after RFC 6455 reassembly/validation. */
    static byte[] encodeWebSocketBinary(Envelope envelope) { return body(envelope); }
    static Envelope decodeWebSocketBinary(byte[] message) throws IOException { return parse(message); }

    /** TCP framing: 4-byte big-endian envelope byte count, then that envelope. */
    static byte[] encodeTcp(Envelope envelope) {
        byte[] bytes = body(envelope);
        return ByteBuffer.allocate(4 + bytes.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.length).put(bytes).array();
    }

    static void writeTcp(OutputStream output, Envelope envelope) throws IOException {
        Objects.requireNonNull(output, "output");
        output.write(encodeTcp(envelope));
    }

    static Envelope readTcp(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream reader = new DataInputStream(input);
        int length = reader.readInt(); // EOF is a transport terminal, not an empty request.
        if (length < ENVELOPE_BYTES || length > MAX_ENVELOPE_BYTES)
            throw new IOException("invalid TCP unary frame length");
        byte[] payload = new byte[length]; // Bound length BEFORE allocation.
        try { reader.readFully(payload); }
        catch (EOFException incomplete) { throw new EOFException("truncated TCP unary frame"); }
        return parse(payload);
    }
}
