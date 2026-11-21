package dev.oreslang.runtime;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated same-host IPC frame envelope for external actors.
 *
 * <p>V1 deliberately separates DATA and CONTROL sequence domains. A runtime is
 * expected to place them on physically distinct bounded transports so actor
 * payload backpressure cannot starve lifecycle/control delivery.</p>
 *
 * <p>This codec is transport framing only. The payload remains the compiler-
 * bound actor message/state/control encoding. It does not provide exactly-once
 * execution semantics and it does not authorize process launch.</p>
 */
public final class ActorIpcFrameV1 {
    private ActorIpcFrameV1() { }

    private static final byte[] MAGIC = new byte[] {
            'O','R','E','S','I','P','C','1'
    };
    private static final short PROTOCOL_MAJOR = 1;
    private static final short PROTOCOL_MINOR = 0;
    private static final int SESSION_ID_BYTES = 16;
    private static final int SHA256_BYTES = 32;
    private static final int TAG_BYTES = 32;
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_CONTROL_PAYLOAD_BYTES = 64 * 1024;

    public enum Lane {
        DATA(0),
        CONTROL(1);

        private final int wireCode;

        Lane(int wireCode) {
            this.wireCode = wireCode;
        }

        int wireCode() {
            return wireCode;
        }

        static Lane fromWireCode(int code) {
            return switch (code) {
                case 0 -> DATA;
                case 1 -> CONTROL;
                default -> throw violation("unknown frame lane");
            };
        }
    }

    public enum Kind {
        DATA(0, Lane.DATA),
        OUTPUT(1, Lane.DATA),
        COMPLETE(2, Lane.CONTROL),
        CONTROL(3, Lane.CONTROL),
        ERROR(4, Lane.CONTROL);

        private final int wireCode;
        private final Lane lane;

        Kind(int wireCode, Lane lane) {
            this.wireCode = wireCode;
            this.lane = lane;
        }

        int wireCode() {
            return wireCode;
        }

        public Lane lane() {
            return lane;
        }

        static Kind fromWireCode(int code) {
            return switch (code) {
                case 0 -> DATA;
                case 1 -> OUTPUT;
                case 2 -> COMPLETE;
                case 3 -> CONTROL;
                case 4 -> ERROR;
                default -> throw violation("unknown frame kind");
            };
        }
    }

    /** Immutable route/session identity for exactly one physical lane. */
    public record SessionBinding(
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            Lane lane,
            byte[] sessionId,
            byte[] messageAbiSha256) {
        public SessionBinding {
            logicalActorId = Objects.requireNonNull(logicalActorId, "logicalActorId");
            if (incarnation <= 0) {
                throw new IllegalArgumentException("incarnation must be positive");
            }
            if (routeEpoch <= 0) {
                throw new IllegalArgumentException("routeEpoch must be positive");
            }
            lane = Objects.requireNonNull(lane, "lane");
            sessionId = requireNonZeroBytes(
                    sessionId, SESSION_ID_BYTES, "sessionId");
            messageAbiSha256 =
                    requireBytes(messageAbiSha256, SHA256_BYTES, "messageAbiSha256");
        }

        @Override public byte[] sessionId() { return sessionId.clone(); }
        @Override public byte[] messageAbiSha256() {
            return messageAbiSha256.clone();
        }
    }

    public record DecodedFrame(
            Kind kind,
            long frameSequence,
            long messageSequence,
            byte[] payload) {
        public DecodedFrame {
            kind = Objects.requireNonNull(kind, "kind");
            if (frameSequence <= 0) {
                throw new IllegalArgumentException("frameSequence must be positive");
            }
            if (messageSequence < 0) {
                throw new IllegalArgumentException("messageSequence cannot be negative");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("payload exceeds IPC v1 bound");
            }
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    /** Stateful single-direction encoder; never share one instance between lanes. */
    public static final class Encoder {
        private final SessionBinding binding;
        private final byte[] authenticationKey;
        private long nextSequence = 1;

        public Encoder(SessionBinding binding, byte[] authenticationKey) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.authenticationKey =
                    requireNonZeroBytes(
                            authenticationKey,
                            SHA256_BYTES,
                            "authenticationKey");
        }

        public synchronized long nextFrameSequence() {
            return nextSequence;
        }

        public synchronized byte[] encode(
                Kind kind,
                long messageSequence,
                byte[] payload) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
            if (kind.lane() != binding.lane()) {
                throw new IllegalArgumentException(
                        "frame kind " + kind + " is not valid on " + binding.lane() + " lane");
            }
            validateMessageSequence(kind, messageSequence);
            int maxPayload = maxPayloadBytes(binding.lane());
            if (payload.length > maxPayload) {
                throw new IllegalArgumentException(
                        "payload exceeds IPC v1 " + binding.lane()
                                + " bound of " + maxPayload);
            }

            long sequence = nextSequence;
            byte[] unsigned = encodeUnsigned(
                    binding, kind, sequence, messageSequence, payload);
            byte[] tag = hmac(authenticationKey, unsigned);
            byte[] result = new byte[unsigned.length + TAG_BYTES];
            System.arraycopy(unsigned, 0, result, 0, unsigned.length);
            System.arraycopy(tag, 0, result, unsigned.length, TAG_BYTES);
            nextSequence = Math.addExact(sequence, 1L);
            return result;
        }
    }

    /** Stateful single-direction decoder with strict anti-replay ordering. */
    public static final class Decoder {
        private final SessionBinding binding;
        private final byte[] authenticationKey;
        private long nextExpectedSequence = 1;

        public Decoder(SessionBinding binding, byte[] authenticationKey) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.authenticationKey =
                    requireNonZeroBytes(
                            authenticationKey,
                            SHA256_BYTES,
                            "authenticationKey");
        }

        public synchronized long nextExpectedFrameSequence() {
            return nextExpectedSequence;
        }

        public synchronized DecodedFrame decode(byte[] frame) {
            Objects.requireNonNull(frame, "frame");
            if (frame.length < minimumFrameBytes()) {
                throw violation("truncated frame");
            }
            if (frame.length > minimumFrameBytes() + MAX_PAYLOAD_BYTES) {
                throw violation("frame exceeds IPC v1 maximum size");
            }

            // Snapshot the mutable transport buffer before parsing, MAC
            // verification and payload extraction. Otherwise a producer could
            // race mutation after authentication and before payload copying.
            byte[] admittedFrame = frame.clone();
            ParsedHeader header = parseHeader(admittedFrame);
            int unsignedLength = Math.addExact(header.headerBytes, header.payloadLength);
            int expectedTotal = Math.addExact(unsignedLength, TAG_BYTES);
            if (admittedFrame.length != expectedTotal) {
                throw violation("frame length/trailing-byte mismatch");
            }

            byte[] suppliedTag =
                    Arrays.copyOfRange(
                            admittedFrame,
                            unsignedLength,
                            expectedTotal);
            byte[] actualTag = hmac(
                    authenticationKey,
                    admittedFrame,
                    0,
                    unsignedLength);
            if (!MessageDigest.isEqual(suppliedTag, actualTag)) {
                throw violation("authentication failure");
            }

            // Do not trust identity/routing metadata until after MAC verification.
            requireBinding(header);
            if (header.frameSequence != nextExpectedSequence) {
                throw violation(
                        "unexpected frame sequence; expected "
                                + nextExpectedSequence + " but received "
                                + header.frameSequence);
            }
            if (header.kind.lane() != binding.lane()) {
                throw violation("frame kind is on the wrong physical lane");
            }

            byte[] payload = Arrays.copyOfRange(
                    admittedFrame,
                    header.headerBytes,
                    unsignedLength);
            DecodedFrame decoded = new DecodedFrame(
                    header.kind,
                    header.frameSequence,
                    header.messageSequence,
                    payload);
            nextExpectedSequence =
                    Math.addExact(nextExpectedSequence, 1L);
            return decoded;
        }

        private void requireBinding(ParsedHeader header) {
            if (header.protocolMajor != PROTOCOL_MAJOR
                    || header.protocolMinor != PROTOCOL_MINOR
                    || !header.logicalActorId.equals(binding.logicalActorId())
                    || header.incarnation != binding.incarnation()
                    || header.routeEpoch != binding.routeEpoch()
                    || header.lane != binding.lane()
                    || !MessageDigest.isEqual(
                            header.sessionId, binding.sessionId())
                    || !MessageDigest.isEqual(
                            header.messageAbiSha256,
                            binding.messageAbiSha256())) {
                throw violation("session/route/ABI binding mismatch");
            }
        }
    }

    private record ParsedHeader(
            short protocolMajor,
            short protocolMinor,
            Kind kind,
            Lane lane,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            byte[] sessionId,
            long frameSequence,
            long messageSequence,
            byte[] messageAbiSha256,
            int payloadLength,
            int headerBytes) { }

    private static byte[] encodeUnsigned(
            SessionBinding binding,
            Kind kind,
            long frameSequence,
            long messageSequence,
            byte[] payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    minimumFrameBytes() + payload.length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeShort(PROTOCOL_MAJOR);
            out.writeShort(PROTOCOL_MINOR);
            out.writeByte(kind.wireCode());
            out.writeByte(binding.lane().wireCode());
            out.writeLong(binding.logicalActorId().getMostSignificantBits());
            out.writeLong(binding.logicalActorId().getLeastSignificantBits());
            out.writeLong(binding.incarnation());
            out.writeLong(binding.routeEpoch());
            out.write(binding.sessionId());
            out.writeLong(frameSequence);
            out.writeLong(messageSequence);
            out.write(binding.messageAbiSha256());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory IPC frame encoding failed", impossible);
        }
    }

    private static ParsedHeader parseHeader(byte[] frame) {
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(frame);
            DataInputStream in = new DataInputStream(bytes);

            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw violation("invalid frame magic");
            }
            short major = in.readShort();
            short minor = in.readShort();

            Kind kind = Kind.fromWireCode(in.readUnsignedByte());
            Lane lane = Lane.fromWireCode(in.readUnsignedByte());

            UUID logicalActor = new UUID(in.readLong(), in.readLong());
            long incarnation = in.readLong();
            long routeEpoch = in.readLong();
            byte[] sessionId = in.readNBytes(SESSION_ID_BYTES);
            if (sessionId.length != SESSION_ID_BYTES) {
                throw violation("truncated session id");
            }
            long frameSequence = in.readLong();
            long messageSequence = in.readLong();
            byte[] messageAbi = in.readNBytes(SHA256_BYTES);
            if (messageAbi.length != SHA256_BYTES) {
                throw violation("truncated message ABI digest");
            }
            int payloadLength = in.readInt();

            if (incarnation <= 0 || routeEpoch <= 0
                    || frameSequence <= 0 || messageSequence < 0) {
                throw violation("invalid non-positive identity/sequence value");
            }
            try {
                validateMessageSequence(kind, messageSequence);
            } catch (IllegalArgumentException invalidSequence) {
                throw violation(invalidSequence.getMessage());
            }
            int maxPayload = maxPayloadBytes(lane);
            if (payloadLength < 0 || payloadLength > maxPayload) {
                throw violation(
                        "payload length exceeds IPC v1 " + lane + " bound");
            }

            return new ParsedHeader(
                    major,
                    minor,
                    kind,
                    lane,
                    logicalActor,
                    incarnation,
                    routeEpoch,
                    sessionId,
                    frameSequence,
                    messageSequence,
                    messageAbi,
                    payloadLength,
                    frame.length - bytes.available());
        } catch (EOFException truncated) {
            throw violation("truncated frame");
        } catch (IOException impossible) {
            throw new AssertionError("in-memory IPC frame parsing failed", impossible);
        }
    }

    private static void validateMessageSequence(
            Kind kind,
            long messageSequence) {
        if (messageSequence < 0) {
            throw new IllegalArgumentException(
                    "messageSequence cannot be negative");
        }
        if ((kind == Kind.DATA
                || kind == Kind.OUTPUT
                || kind == Kind.COMPLETE)
                && messageSequence == 0) {
            throw new IllegalArgumentException(
                    kind + " requires a positive logical message sequence");
        }
    }

    private static int maxPayloadBytes(Lane lane) {
        return lane == Lane.CONTROL
                ? MAX_CONTROL_PAYLOAD_BYTES
                : MAX_PAYLOAD_BYTES;
    }

    private static int minimumFrameBytes() {
        // Header plus authentication tag, zero-byte payload.
        return MAGIC.length
                + Short.BYTES * 2
                + 2
                + Long.BYTES * 6
                + SESSION_ID_BYTES
                + SHA256_BYTES
                + Integer.BYTES
                + TAG_BYTES;
    }

    private static byte[] hmac(byte[] key, byte[] bytes) {
        return hmac(key, bytes, 0, bytes.length);
    }

    private static byte[] hmac(
            byte[] key,
            byte[] bytes,
            int offset,
            int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(bytes, offset, length);
            return mac.doFinal();
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError("JDK must provide HmacSHA256", impossible);
        }
    }

    private static byte[] requireNonZeroBytes(
            byte[] value,
            int expectedLength,
            String label) {
        byte[] copy = requireBytes(value, expectedLength, label);
        int nonZero = 0;
        for (byte b : copy) nonZero |= b;
        if (nonZero == 0) {
            throw new IllegalArgumentException(label + " cannot be all zero");
        }
        return copy;
    }

    private static byte[] requireBytes(
            byte[] value,
            int expectedLength,
            String label) {
        Objects.requireNonNull(value, label);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(
                    label + " must be exactly " + expectedLength + " bytes");
        }
        return value.clone();
    }

    private static SecurityException violation(String detail) {
        return new SecurityException(
                "actor IPC v1 protocol violation: " + detail);
    }
}
