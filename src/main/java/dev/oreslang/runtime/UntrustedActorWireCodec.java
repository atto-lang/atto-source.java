package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * A bounded, versioned, data-only envelope for the future host-to-one-isolate
 * actor mailbox bridge. No Java object graphs, ActorRef instances, handles or
 * native pointers cross this representation. This codec does NOT perform
 * delivery; the guest message router remains a separate integration gate.
 *
 * <p>A domain restart must change the epoch. Readers MUST supply their current
 * epoch when decoding to reject stale replies from prior isolate instances.
 * CRC32C catches accidental corruption; it is not cryptographic authentication.
 * Host/guest endpoints are mutually trusted at the wire level, not necessarily
 * at the actor payload level.</p>
 */
final class UntrustedActorWireCodec {
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAGIC = 0x4f524553; // ORES
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = 48;
    private static final UUID NO_ACTOR = new UUID(0, 0);

    enum Operation {
        SPAWN(1), SEND(2), STOP(3), ACCEPTED(4), FAILED(5);
        final int code;
        Operation(int code) { this.code = code; }
        static Operation fromCode(int code) {
            for (Operation op : values()) if (op.code == code) return op;
            throw new IllegalArgumentException("unsupported untrusted actor wire operation " + code);
        }
    }

    record Frame(Operation operation, long epoch, long requestId,
                 UUID actorId, byte[] payload) {
        Frame {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(payload, "payload");
            if (epoch <= 0 || requestId <= 0)
                throw new IllegalArgumentException("isolate epoch and request ID must be positive");
            if (payload.length > MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("untrusted actor frame exceeds payload limit");
            if ((operation == Operation.SEND || operation == Operation.STOP
                    || operation == Operation.ACCEPTED) && actorId.equals(NO_ACTOR))
                throw new IllegalArgumentException("actor identity required for " + operation);
            if (operation == Operation.SPAWN && !actorId.equals(NO_ACTOR))
                throw new IllegalArgumentException("SPAWN cannot claim an existing actor identity");
            payload = payload.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    private UntrustedActorWireCodec() { }

    static byte[] encode(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] body = frame.payload();
        int crc = checksum(body);
        ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES + body.length).order(ByteOrder.BIG_ENDIAN);
        bytes.putInt(MAGIC);
        bytes.put(VERSION);
        bytes.put((byte) frame.operation().code);
        bytes.putShort((short) 0); // reserved flags, must remain zero
        bytes.putLong(frame.epoch());
        bytes.putLong(frame.requestId());
        bytes.putLong(frame.actorId().getMostSignificantBits());
        bytes.putLong(frame.actorId().getLeastSignificantBits());
        bytes.putInt(body.length);
        bytes.putInt(crc);
        bytes.put(body);
        return bytes.array();
    }

    static Frame decode(byte[] wire, long expectedEpoch) {
        Objects.requireNonNull(wire, "wire");
        if (expectedEpoch <= 0) throw new IllegalArgumentException("expected epoch must be positive");
        if (wire.length < HEADER_BYTES || wire.length > HEADER_BYTES + MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("truncated or oversized untrusted actor frame");
        ByteBuffer input = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (input.getInt() != MAGIC) throw new IllegalArgumentException("invalid actor frame magic");
        if (input.get() != VERSION) throw new IllegalArgumentException("unsupported actor frame version");
        Operation operation = Operation.fromCode(Byte.toUnsignedInt(input.get()));
        if (input.getShort() != 0) throw new IllegalArgumentException("reserved actor frame bits set");
        long epoch = input.getLong();
        if (epoch != expectedEpoch) throw new IllegalArgumentException("stale isolate epoch");
        long requestId = input.getLong();
        UUID actorId = new UUID(input.getLong(), input.getLong());
        int size = input.getInt();
        int expectedChecksum = input.getInt();
        if (size < 0 || size > MAX_PAYLOAD_BYTES || size != input.remaining())
            throw new IllegalArgumentException("invalid untrusted actor payload length");
        byte[] body = new byte[size];
        input.get(body);
        if (checksum(body) != expectedChecksum)
            throw new IllegalArgumentException("untrusted actor frame CRC mismatch");
        return new Frame(operation, epoch, requestId, actorId, body);
    }

    private static int checksum(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue();
    }
}
