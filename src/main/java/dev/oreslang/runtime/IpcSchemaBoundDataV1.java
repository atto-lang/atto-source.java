package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Opt-in schema-bound DATA subprofile inside the existing authenticated v3
 * payload. No fallback to legacy DATA, no decoder selection from wire data.
 * The trusted endpoint registers its binding independently of the peer.
 */
final class IpcSchemaBoundDataV1 {
    private static final int MAGIC = 0x41534431; // ASD1
    private static final int MAX_BODY = 1024 * 1024;
    private static final int MAX_SCHEMA = 256;
    private static final int MAX_WRAPPED = MAX_BODY + 4 + 1 + 4 + 2 + MAX_SCHEMA + 32 + 4;
    static final int MAX_AUTHENTICATED_FRAME = MAX_WRAPPED + 1024;

    enum Codec {
        MESSAGEPACK(1), PROTOBUF(2), CBOR(3), FLATBUFFERS(4);
        private final int wire;
        Codec(int wire) { this.wire = wire; }
    }

    static final class Binding {
        private final Codec codec;
        private final int version;
        private final byte[] schema;
        private final byte[] digest;

        Binding(Codec codec, int version, String schema, byte[] shapeDigest) {
            this.codec = Objects.requireNonNull(codec, "codec");
            if (version <= 0 || schema == null || schema.isEmpty()
                    || schema.length() > MAX_SCHEMA
                    || !schema.chars().allMatch(c -> c >= 33 && c <= 126)
                    || shapeDigest == null || shapeDigest.length != 32) {
                throw new IllegalArgumentException("invalid trusted schema binding");
            }
            this.version = version;
            this.schema = schema.getBytes(StandardCharsets.US_ASCII);
            this.digest = shapeDigest.clone();
        }
    }

    private IpcSchemaBoundDataV1() { }

    /** Caller signs the entire returned payload using the existing DATA frame. */
    static byte[] wrap(Binding binding, byte[] body) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY) throw new IOException("schema DATA body too large");
        ByteArrayOutputStream raw = new ByteArrayOutputStream(
                47 + binding.schema.length + body.length);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(MAGIC);
            out.writeByte(binding.codec.wire);
            out.writeInt(binding.version);
            out.writeShort(binding.schema.length);
            out.write(binding.schema);
            out.write(binding.digest);
            out.writeInt(body.length);
            out.write(body);
        }
        return raw.toByteArray();
    }

    /** MAC, physical epoch, recipient incarnation and replay precede parsing. */
    static byte[] authenticateAndUnwrap(
            byte[] frame, IpcReceiptCorrelation.Envelope expectedEpoch,
            long physicalGeneration, IpcAuthenticatedSocketTransport.InboundReplayGate replay,
            byte[] connectionBinding, byte[] key, Binding expected) throws IOException {
        Objects.requireNonNull(expected, "expected binding");
        // Bound the entire unauthenticated frame before the outer decoder's copy.
        // The existing v3 maximum envelope overhead is at most 1024 bytes.
        if (frame == null || frame.length > MAX_AUTHENTICATED_FRAME) {
            throw new IOException("schema DATA frame too large");
        }
        var authenticated = IpcAuthenticatedSocketTransport.decodeDataFrame(
                frame, expectedEpoch, physicalGeneration, replay, connectionBinding, key);
        return unwrap(authenticated.payload(), expected);
    }

    private static byte[] unwrap(byte[] wrapped, Binding expected) throws IOException {
        if (wrapped.length > MAX_WRAPPED) throw new IOException("schema DATA payload too large");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wrapped))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != expected.codec.wire
                    || in.readInt() != expected.version) {
                throw new IOException("schema DATA profile/codec/version mismatch");
            }
            int length = in.readUnsignedShort();
            if (length != expected.schema.length) throw new IOException("schema identity mismatch");
            byte[] schema = in.readNBytes(length);
            byte[] digest = in.readNBytes(32);
            if (!MessageDigest.isEqual(schema, expected.schema)
                    || !MessageDigest.isEqual(digest, expected.digest)) {
                throw new IOException("schema identity or shape digest mismatch");
            }
            int bodyLength = in.readInt();
            if (bodyLength < 0 || bodyLength > MAX_BODY || bodyLength != in.available()) {
                throw new IOException("invalid schema DATA body length");
            }
            byte[] body = in.readNBytes(bodyLength);
            if (body.length != bodyLength || in.available() != 0) {
                throw new IOException("truncated schema DATA body");
            }
            return body;
        }
    }
}
