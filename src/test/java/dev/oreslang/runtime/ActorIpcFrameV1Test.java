package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ActorIpcFrameV1Test {

    @Test
    void dataRoundTripUsesStrictFrameSequenceAndDefensivePayloadCopies() {
        byte[] key = filled(32, (byte) 0x31);
        var binding = binding(ActorIpcFrameV1.Lane.DATA, 7L, 11L, (byte) 0x41);
        var encoder = new ActorIpcFrameV1.Encoder(binding, key);
        var decoder = new ActorIpcFrameV1.Decoder(binding, key);

        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] wire = encoder.encode(
                ActorIpcFrameV1.Kind.DATA,
                42L,
                payload);
        payload[0] = 'X';

        var decoded = decoder.decode(wire);
        assertEquals(ActorIpcFrameV1.Kind.DATA, decoded.kind());
        assertEquals(1L, decoded.frameSequence());
        assertEquals(42L, decoded.messageSequence());
        assertEquals("hello", new String(decoded.payload(), StandardCharsets.UTF_8));
        assertEquals(2L, encoder.nextFrameSequence());
        assertEquals(2L, decoder.nextExpectedFrameSequence());

        byte[] exported = decoded.payload();
        exported[0] = 'Y';
        assertEquals('h', decoded.payload()[0]);
    }

    @Test
    void replayAndGapFailWithoutAdvancingDecoder() {
        byte[] key = filled(32, (byte) 0x22);
        var binding = binding(ActorIpcFrameV1.Lane.DATA, 1L, 1L, (byte) 0x33);
        var encoder = new ActorIpcFrameV1.Encoder(binding, key);
        var decoder = new ActorIpcFrameV1.Decoder(binding, key);

        byte[] first = encoder.encode(
                ActorIpcFrameV1.Kind.DATA, 1L, new byte[] {1});
        byte[] second = encoder.encode(
                ActorIpcFrameV1.Kind.DATA, 2L, new byte[] {2});

        SecurityException gap = assertThrows(
                SecurityException.class,
                () -> decoder.decode(second));
        assertTrue(gap.getMessage().contains("expected 1"), gap.getMessage());
        assertEquals(1L, decoder.nextExpectedFrameSequence());

        assertEquals(1L, decoder.decode(first).frameSequence());
        assertEquals(2L, decoder.decode(second).frameSequence());

        SecurityException replay = assertThrows(
                SecurityException.class,
                () -> decoder.decode(second));
        assertTrue(replay.getMessage().contains("expected 3"), replay.getMessage());
        assertEquals(3L, decoder.nextExpectedFrameSequence());
    }

    @Test
    void staleRouteWrongIncarnationSessionAbiAndLaneAreRejected() {
        byte[] key = filled(32, (byte) 0x5a);
        var live = binding(ActorIpcFrameV1.Lane.DATA, 4L, 9L, (byte) 0x60);
        byte[] wire = new ActorIpcFrameV1.Encoder(live, key).encode(
                ActorIpcFrameV1.Kind.DATA,
                77L,
                "payload".getBytes(StandardCharsets.UTF_8));

        assertBindingRejected(wire, key, binding(
                ActorIpcFrameV1.Lane.DATA, 4L, 8L, (byte) 0x60));
        assertBindingRejected(wire, key, binding(
                ActorIpcFrameV1.Lane.DATA, 3L, 9L, (byte) 0x60));
        assertBindingRejected(wire, key, binding(
                ActorIpcFrameV1.Lane.DATA, 4L, 9L, (byte) 0x61));

        byte[] otherAbi = sha256("other-message-abi".getBytes(StandardCharsets.UTF_8));
        var wrongAbi = new ActorIpcFrameV1.SessionBinding(
                live.logicalActorId(),
                live.incarnation(),
                live.routeEpoch(),
                live.lane(),
                live.sessionId(),
                otherAbi);
        assertBindingRejected(wire, key, wrongAbi);

        var control = new ActorIpcFrameV1.SessionBinding(
                live.logicalActorId(),
                live.incarnation(),
                live.routeEpoch(),
                ActorIpcFrameV1.Lane.CONTROL,
                live.sessionId(),
                live.messageAbiSha256());
        assertBindingRejected(wire, key, control);
    }

    @Test
    void wrongAuthenticationKeyPayloadForgeryTruncationAndTrailingBytesFail() {
        byte[] key = filled(32, (byte) 0x21);
        var binding = binding(ActorIpcFrameV1.Lane.DATA, 1L, 2L, (byte) 0x44);
        byte[] wire = new ActorIpcFrameV1.Encoder(binding, key).encode(
                ActorIpcFrameV1.Kind.DATA,
                1L,
                "secret".getBytes(StandardCharsets.UTF_8));

        assertThrows(
                SecurityException.class,
                () -> new ActorIpcFrameV1.Decoder(
                        binding, filled(32, (byte) 0x23)).decode(wire));

        byte[] forged = wire.clone();
        forged[forged.length - 33] ^= 1; // last payload byte, before 32-byte tag
        assertThrows(
                SecurityException.class,
                () -> new ActorIpcFrameV1.Decoder(binding, key).decode(forged));

        for (int trim : new int[] {1, 8, 31, 32, wire.length - 1}) {
            byte[] truncated = Arrays.copyOf(wire, wire.length - trim);
            assertThrows(
                    SecurityException.class,
                    () -> new ActorIpcFrameV1.Decoder(binding, key).decode(truncated));
        }

        byte[] trailing = Arrays.copyOf(wire, wire.length + 1);
        trailing[trailing.length - 1] = 1;
        assertThrows(
                SecurityException.class,
                () -> new ActorIpcFrameV1.Decoder(binding, key).decode(trailing));
    }

    @Test
    void controlLaneCannotBeStarvedSemanticallyByDataSequenceAndAllowsOutOfOrderCompletions() {
        byte[] dataKey = filled(32, (byte) 0x11);
        byte[] controlKey = filled(32, (byte) 0x12);
        UUID actor = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] messageAbi = sha256("abi".getBytes(StandardCharsets.UTF_8));

        var dataBinding = new ActorIpcFrameV1.SessionBinding(
                actor, 2L, 5L, ActorIpcFrameV1.Lane.DATA,
                filled(16, (byte) 0x31), messageAbi);
        var controlBinding = new ActorIpcFrameV1.SessionBinding(
                actor, 2L, 5L, ActorIpcFrameV1.Lane.CONTROL,
                filled(16, (byte) 0x32), messageAbi);

        var dataEncoder = new ActorIpcFrameV1.Encoder(dataBinding, dataKey);
        var controlEncoder = new ActorIpcFrameV1.Encoder(controlBinding, controlKey);
        var dataDecoder = new ActorIpcFrameV1.Decoder(dataBinding, dataKey);
        var controlDecoder = new ActorIpcFrameV1.Decoder(controlBinding, controlKey);

        for (long seq = 1; seq <= 100; seq++) {
            dataEncoder.encode(
                    ActorIpcFrameV1.Kind.DATA,
                    seq,
                    new byte[] {(byte) seq});
        }

        byte[] completeLaterMessage = controlEncoder.encode(
                ActorIpcFrameV1.Kind.COMPLETE,
                99L,
                new byte[] {9});
        byte[] completeEarlierMessage = controlEncoder.encode(
                ActorIpcFrameV1.Kind.COMPLETE,
                3L,
                new byte[] {3});

        var first = controlDecoder.decode(completeLaterMessage);
        var second = controlDecoder.decode(completeEarlierMessage);
        assertEquals(1L, first.frameSequence());
        assertEquals(99L, first.messageSequence());
        assertEquals(2L, second.frameSequence());
        assertEquals(3L, second.messageSequence());

        // DATA has an independent sequence domain; no DATA frame was consumed.
        assertEquals(1L, dataDecoder.nextExpectedFrameSequence());
        assertEquals(101L, dataEncoder.nextFrameSequence());
    }

    @Test
    void kindMustMatchPhysicalLaneAndSessionsRequireEntropy() {
        byte[] key = filled(32, (byte) 0x41);
        var data = binding(ActorIpcFrameV1.Lane.DATA, 1L, 1L, (byte) 0x51);
        var control = binding(ActorIpcFrameV1.Lane.CONTROL, 1L, 1L, (byte) 0x52);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.Encoder(data, key).encode(
                        ActorIpcFrameV1.Kind.CONTROL, 0L, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.Encoder(control, key).encode(
                        ActorIpcFrameV1.Kind.DATA, 0L, new byte[0]));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.Encoder(data, new byte[32]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.SessionBinding(
                        data.logicalActorId(),
                        1L,
                        1L,
                        ActorIpcFrameV1.Lane.DATA,
                        new byte[16],
                        data.messageAbiSha256()));
    }

    @Test
    void payloadBoundIsEnforcedBeforeEncodingAndDuringDecode() {
        byte[] key = filled(32, (byte) 0x7a);
        var binding = binding(ActorIpcFrameV1.Lane.DATA, 1L, 1L, (byte) 0x6a);
        var encoder = new ActorIpcFrameV1.Encoder(binding, key);

        byte[] maximum = new byte[1024 * 1024];
        byte[] frame = encoder.encode(
                ActorIpcFrameV1.Kind.DATA,
                1L,
                maximum);
        assertEquals(
                maximum.length,
                new ActorIpcFrameV1.Decoder(binding, key)
                        .decode(frame).payload().length);

        assertThrows(
                IllegalArgumentException.class,
                () -> encoder.encode(
                        ActorIpcFrameV1.Kind.DATA,
                        2L,
                        new byte[1024 * 1024 + 1]));
    }

    @Test
    void wireCodesRemainStableAndControlPayloadsStayTightlyBounded() {
        byte[] key = filled(32, (byte) 0x66);
        var data = binding(
                ActorIpcFrameV1.Lane.DATA, 1L, 1L, (byte) 0x71);
        var control = binding(
                ActorIpcFrameV1.Lane.CONTROL, 1L, 1L, (byte) 0x72);

        byte[] dataFrame = new ActorIpcFrameV1.Encoder(data, key).encode(
                ActorIpcFrameV1.Kind.DATA,
                1L,
                new byte[0]);
        assertEquals(0, Byte.toUnsignedInt(dataFrame[12]),
                "DATA wire code is part of IPC v1");
        assertEquals(0, Byte.toUnsignedInt(dataFrame[13]),
                "DATA lane wire code is part of IPC v1");

        byte[] controlFrame =
                new ActorIpcFrameV1.Encoder(control, key).encode(
                        ActorIpcFrameV1.Kind.CONTROL,
                        0L,
                        new byte[64 * 1024]);
        assertEquals(3, Byte.toUnsignedInt(controlFrame[12]),
                "CONTROL wire code is part of IPC v1");
        assertEquals(1, Byte.toUnsignedInt(controlFrame[13]),
                "CONTROL lane wire code is part of IPC v1");
        assertEquals(
                64 * 1024,
                new ActorIpcFrameV1.Decoder(control, key)
                        .decode(controlFrame).payload().length);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.Encoder(control, key).encode(
                        ActorIpcFrameV1.Kind.CONTROL,
                        0L,
                        new byte[64 * 1024 + 1]));
    }

    @Test
    void messageScopedKindsRequirePositiveLogicalMessageSequence() {
        byte[] key = filled(32, (byte) 0x67);
        var data = binding(
                ActorIpcFrameV1.Lane.DATA, 1L, 1L, (byte) 0x73);
        var control = binding(
                ActorIpcFrameV1.Lane.CONTROL, 1L, 1L, (byte) 0x74);

        var dataEncoder = new ActorIpcFrameV1.Encoder(data, key);
        assertThrows(
                IllegalArgumentException.class,
                () -> dataEncoder.encode(
                        ActorIpcFrameV1.Kind.DATA, 0L, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> dataEncoder.encode(
                        ActorIpcFrameV1.Kind.OUTPUT, 0L, new byte[0]));

        var controlEncoder = new ActorIpcFrameV1.Encoder(control, key);
        assertThrows(
                IllegalArgumentException.class,
                () -> controlEncoder.encode(
                        ActorIpcFrameV1.Kind.COMPLETE, 0L, new byte[0]));

        assertDoesNotThrow(
                () -> new ActorIpcFrameV1.Encoder(control, key).encode(
                        ActorIpcFrameV1.Kind.CONTROL, 0L, new byte[0]));
        assertDoesNotThrow(
                () -> new ActorIpcFrameV1.Encoder(control, key).encode(
                        ActorIpcFrameV1.Kind.ERROR, 0L, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorIpcFrameV1.Decoder(data, new byte[32]));
    }

    private static void assertBindingRejected(
            byte[] wire,
            byte[] key,
            ActorIpcFrameV1.SessionBinding binding) {
        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> new ActorIpcFrameV1.Decoder(binding, key).decode(wire));
        assertTrue(
                failure.getMessage().contains("binding mismatch"),
                failure.getMessage());
    }

    private static ActorIpcFrameV1.SessionBinding binding(
            ActorIpcFrameV1.Lane lane,
            long incarnation,
            long routeEpoch,
            byte sessionByte) {
        return new ActorIpcFrameV1.SessionBinding(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                incarnation,
                routeEpoch,
                lane,
                filled(16, sessionByte),
                sha256("message-abi".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] filled(int size, byte value) {
        byte[] result = new byte[size];
        Arrays.fill(result, value);
        return result;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
