package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UntrustedActorWireCodecTest {
    private static final long EPOCH = 73;
    private static final UUID ACTOR = UUID.fromString("a5fb31d4-9bb0-41e8-8313-4dc11e2d62ea");

    @Test
    void roundTripsBoundedDataWithoutLeakingAliases() {
        byte[] mutable = new byte[]{1, 2, 3, 4};
        var frame = new UntrustedActorWireCodec.Frame(
                UntrustedActorWireCodec.Operation.SEND, EPOCH, 17, ACTOR, mutable);
        mutable[0] = 99;
        byte[] result = frame.payload();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
        result[1] = 77;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, frame.payload());

        var decoded = UntrustedActorWireCodec.decode(
                UntrustedActorWireCodec.encode(frame), EPOCH);
        assertEquals(frame.operation(), decoded.operation());
        assertEquals(frame.epoch(), decoded.epoch());
        assertEquals(frame.requestId(), decoded.requestId());
        assertEquals(frame.actorId(), decoded.actorId());
        assertArrayEquals(frame.payload(), decoded.payload());
    }

    @Test
    void spawningRequiresNoPriorActorIdentity() {
        var request = new UntrustedActorWireCodec.Frame(
                UntrustedActorWireCodec.Operation.SPAWN, EPOCH, 1,
                new UUID(0, 0), new byte[]{12});
        assertEquals(UntrustedActorWireCodec.Operation.SPAWN,
                UntrustedActorWireCodec.decode(
                        UntrustedActorWireCodec.encode(request), EPOCH).operation());
        assertThrows(IllegalArgumentException.class, () ->
                new UntrustedActorWireCodec.Frame(
                        UntrustedActorWireCodec.Operation.SPAWN, EPOCH, 1, ACTOR, new byte[0]));
        assertThrows(IllegalArgumentException.class, () ->
                new UntrustedActorWireCodec.Frame(
                        UntrustedActorWireCodec.Operation.SEND, EPOCH, 1,
                        new UUID(0, 0), new byte[0]));
    }

    @Test
    void rejectsStaleEpochAndMalformedFrames() {
        var request = new UntrustedActorWireCodec.Frame(
                UntrustedActorWireCodec.Operation.SEND, EPOCH, 1, ACTOR, new byte[]{8, 9});
        byte[] frame = UntrustedActorWireCodec.encode(request);
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(frame, EPOCH + 1));

        byte[] corrupt = frame.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(corrupt, EPOCH));
        byte[] flags = frame.clone();
        flags[7] = 1;
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(flags, EPOCH));
        byte[] version = frame.clone();
        version[4] = 2;
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(version, EPOCH));
        byte[] opcode = frame.clone();
        opcode[5] = 127;
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(opcode, EPOCH));
        byte[] truncated = Arrays.copyOf(frame, frame.length - 1);
        assertThrows(IllegalArgumentException.class, () ->
                UntrustedActorWireCodec.decode(truncated, EPOCH));
    }

    @Test
    void payloadSizeIsBoundedWithoutDeserializingObjects() {
        byte[] max = new byte[UntrustedActorWireCodec.MAX_PAYLOAD_BYTES];
        var frame = new UntrustedActorWireCodec.Frame(
                UntrustedActorWireCodec.Operation.SEND, EPOCH, 33, ACTOR, max);
        assertEquals(max.length, UntrustedActorWireCodec.decode(
                UntrustedActorWireCodec.encode(frame), EPOCH).payload().length);
        assertThrows(IllegalArgumentException.class, () ->
                new UntrustedActorWireCodec.Frame(
                        UntrustedActorWireCodec.Operation.SEND, EPOCH, 34, ACTOR,
                        new byte[max.length + 1]));
    }
}
