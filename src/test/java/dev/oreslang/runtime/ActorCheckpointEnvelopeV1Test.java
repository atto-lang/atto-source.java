package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/** Wire-only tests: no claims of compiler-created actor state or signed code. */
final class ActorCheckpointEnvelopeV1Test {
    private static String digest(char value) { return String.valueOf(value).repeat(64); }
    private static final UUID ID = UUID.fromString("12345678-1234-4321-8123-000000000001");
    private static ActorCheckpointEnvelopeV1.Header expected() {
        return new ActorCheckpointEnvelopeV1.Header(
                ID, 42, digest('a'), digest('b'), digest('c'), digest('d'));
    }

    @Test
    void matchesIndependentNativePythonWireGolden() throws Exception {
        byte[] bytes = ActorCheckpointEnvelopeV1.encode(
                new ActorCheckpointEnvelopeV1.Snapshot(
                        expected(), new byte[]{0x0a, (byte) 0xff}));
        assertEquals(202, bytes.length);
        assertEquals("c054268972740370c1be5ff20ceeda21edd17282a69692203acca15b4e6c1793",
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void canonicalRoundTripWithFullImmutability() {
        byte[] original = {1, 2, 3, 4, (byte) 0xff};
        var checkpoint = new ActorCheckpointEnvelopeV1.Snapshot(expected(), original);
        original[0] = 99;
        byte[] leaked = checkpoint.stateBytes();
        leaked[1] = 99;
        assertArrayEquals(new byte[]{1, 2, 3, 4, (byte) 0xff}, checkpoint.stateBytes());

        byte[] frame = ActorCheckpointEnvelopeV1.encode(checkpoint);
        var restored = ActorCheckpointEnvelopeV1.decode(frame, expected());
        assertEquals(expected(), restored.header());
        assertArrayEquals(checkpoint.stateBytes(), restored.stateBytes());
        assertArrayEquals(frame, ActorCheckpointEnvelopeV1.encode(restored));
    }

    @Test
    void rejectsTamperingTruncationExtraTrailingBytesAndLengthConfusion() {
        byte[] good = ActorCheckpointEnvelopeV1.encode(
                new ActorCheckpointEnvelopeV1.Snapshot(expected(), new byte[]{10, 20, 30}));
        for (int i = 0; i < good.length; i++) {
            byte[] forged = good.clone();
            forged[i] ^= 1;
            assertThrows(RuntimeException.class,
                    () -> ActorCheckpointEnvelopeV1.decode(forged, expected()),
                    "single-bit mutation at " + i);
        }
        assertThrows(IllegalArgumentException.class, () ->
                ActorCheckpointEnvelopeV1.decode(Arrays.copyOf(good, good.length - 1), expected()));
        assertThrows(IllegalArgumentException.class, () ->
                ActorCheckpointEnvelopeV1.decode(Arrays.copyOf(good, good.length + 1), expected()));

        byte[] negative = good.clone();
        // Length appears at end of fixed-size header.
        ByteBuffer.wrap(negative).order(ByteOrder.BIG_ENDIAN)
                .putInt(8 + 4 + 16 + 8 + 4 * 32, -1);
        assertThrows(IllegalArgumentException.class, () ->
                ActorCheckpointEnvelopeV1.decode(negative, expected()));
    }

    @Test
    void refusesForeignActorsEpochsCapabilitiesAndCode() {
        byte[] frame = ActorCheckpointEnvelopeV1.encode(
                new ActorCheckpointEnvelopeV1.Snapshot(expected(), new byte[0]));
        var old = expected();
        for (var mismatch : new ActorCheckpointEnvelopeV1.Header[]{
                new ActorCheckpointEnvelopeV1.Header(UUID.randomUUID(), 42,
                        old.codeDigest(), old.stateSchemaDigest(),
                        old.messageAbiDigest(), old.capabilityDigest()),
                new ActorCheckpointEnvelopeV1.Header(old.actorId(), 43,
                        old.codeDigest(), old.stateSchemaDigest(),
                        old.messageAbiDigest(), old.capabilityDigest()),
                new ActorCheckpointEnvelopeV1.Header(old.actorId(), 42,
                        digest('f'), old.stateSchemaDigest(),
                        old.messageAbiDigest(), old.capabilityDigest()),
                new ActorCheckpointEnvelopeV1.Header(old.actorId(), 42,
                        old.codeDigest(), old.stateSchemaDigest(),
                        old.messageAbiDigest(), digest('f'))
        }) {
            assertThrows(SecurityException.class, () ->
                    ActorCheckpointEnvelopeV1.decode(frame, mismatch));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCheckpointEnvelopeV1.Header(ID, 0,
                        digest('a'), digest('b'), digest('c'), digest('d')));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCheckpointEnvelopeV1.Header(ID, 42,
                        "AB".repeat(32), digest('b'), digest('c'), digest('d')));
    }

    @Test
    void randomizedExactRoundTripsAndByteCap() {
        Random random = new Random(0xC0DEC0DEL);
        for (int i = 0; i < 1024; i++) {
            byte[] bytes = new byte[random.nextInt(500)];
            random.nextBytes(bytes);
            byte[] frame = ActorCheckpointEnvelopeV1.encode(
                    new ActorCheckpointEnvelopeV1.Snapshot(expected(), bytes));
            assertArrayEquals(bytes, ActorCheckpointEnvelopeV1.decode(frame, expected()).stateBytes());
        }
        byte[] limit = new byte[ActorCheckpointEnvelopeV1.MAX_STATE_BYTES];
        assertEquals(limit.length,
                ActorCheckpointEnvelopeV1.decode(
                        ActorCheckpointEnvelopeV1.encode(
                                new ActorCheckpointEnvelopeV1.Snapshot(expected(), limit)),
                        expected()).stateBytes().length);
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCheckpointEnvelopeV1.Snapshot(expected(),
                        new byte[ActorCheckpointEnvelopeV1.MAX_STATE_BYTES + 1]));
    }
}
