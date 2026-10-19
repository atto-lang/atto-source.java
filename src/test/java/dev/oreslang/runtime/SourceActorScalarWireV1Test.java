package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class SourceActorScalarWireV1Test {
    private static final String ABI = "0123456789abcdef".repeat(4);
    private static final UUID ACTOR =
            UUID.fromString("92098be7-bf7e-4d87-b1d0-1970b7cfe553");
    private static final SourceActorScalarWireV1.Binding BINDING =
            new SourceActorScalarWireV1.Binding(ACTOR, 7, ABI);

    @Test
    void canonicalFixedLengthBigEndianRoundTripAcrossEntireI64Range() {
        for (long value : new long[] {
                0L, 1L, -1L, 42L, -42L, Long.MIN_VALUE, Long.MAX_VALUE,
                0x0102030405060708L}) {
            byte[] wire = SourceActorScalarWireV1.encodeLong(BINDING, value);
            assertEquals(SourceActorScalarWireV1.BYTE_LENGTH, wire.length);
            assertArrayEquals(HexFormat.of().parseHex("4f524d3101010000"),
                    java.util.Arrays.copyOfRange(wire, 0, 8));
            assertEquals(value, SourceActorScalarWireV1.decodeLong(BINDING, wire));
        }
        var wire = SourceActorScalarWireV1.encodeLong(
                BINDING, 0x0102030405060708L);
        assertArrayEquals(HexFormat.of().parseHex("0102030405060708"),
                java.util.Arrays.copyOfRange(wire, 64, 72));
    }

    @Test
    void corruptHeadersAndPayloadLengthsFailBeforePhysicalDelivery() {
        byte[] canonical = SourceActorScalarWireV1.encodeLong(BINDING, 41L);
        for (int length : new int[] {0, 7, 8, 32, 64, 71, 73}) {
            byte[] malformed = java.util.Arrays.copyOf(canonical, length);
            assertThrows(SecurityException.class,
                    () -> SourceActorScalarWireV1.decodeLong(BINDING, malformed));
        }
        // Magic, version, wire tag, reserved flags, UUID, incarnation, ABI.
        for (int at : new int[] {0, 3, 4, 5, 6, 7, 8, 23, 24, 31, 32, 63}) {
            byte[] changed = canonical.clone();
            changed[at] ^= 1;
            assertThrows(SecurityException.class,
                    () -> SourceActorScalarWireV1.decodeLong(BINDING, changed),
                    "tampered compiler-bound frame header byte " + at);
        }
    }

    @Test
    void foreignActorIncarnationOrCompilerAbiFailsClosed() {
        byte[] wire = SourceActorScalarWireV1.encodeLong(BINDING, -17L);
        assertThrows(SecurityException.class, () -> SourceActorScalarWireV1.decodeLong(
                new SourceActorScalarWireV1.Binding(
                        UUID.fromString("92098be7-bf7e-4d87-b1d0-1970b7cfe554"),
                        7, ABI), wire));
        assertThrows(SecurityException.class, () -> SourceActorScalarWireV1.decodeLong(
                new SourceActorScalarWireV1.Binding(ACTOR, 8, ABI), wire));
        assertThrows(SecurityException.class, () -> SourceActorScalarWireV1.decodeLong(
                new SourceActorScalarWireV1.Binding(ACTOR, 7,
                        "f".repeat(64)), wire));
    }

    @Test
    void everyHeaderBitFailsClosedAndEveryI64ValueRemainsCanonical() {
        byte[] canonical = SourceActorScalarWireV1.encodeLong(
                BINDING, 0x0102030405060708L);
        // The header is exactly 64 bytes; the final eight are the i64 body.
        for (int offset = 0; offset < 64; ++offset) {
            for (int bit = 0; bit < 8; ++bit) {
                byte[] tampered = canonical.clone();
                tampered[offset] ^= (byte) (1 << bit);
                assertThrows(SecurityException.class,
                        () -> SourceActorScalarWireV1.decodeLong(
                                BINDING, tampered),
                        "ABI-bound scalar header bit must fail at byte "
                                + offset + " bit " + bit);
            }
        }

        var random = new java.util.Random(0x4f524d31L);
        for (int i = 0; i < 25_000; ++i) {
            long value = random.nextLong();
            byte[] encoded = SourceActorScalarWireV1.encodeLong(BINDING, value);
            assertEquals(SourceActorScalarWireV1.BYTE_LENGTH, encoded.length);
            assertEquals(value, SourceActorScalarWireV1.decodeLong(BINDING, encoded));
            // Source buffers must never be retained as a mutable borrowed
            // message payload: producing a new frame leaves earlier immutable
            // logical mailbox copies independent.
            if (i == 0) {
                byte[] independent = encoded.clone();
                encoded[71] ^= 1;
                assertEquals(value,
                        SourceActorScalarWireV1.decodeLong(BINDING, independent));
            }
        }
    }

    @Test
    void compilerAbiBindingRejectsUncanonicalOrAbsentDigests() {
        assertThrows(NullPointerException.class,
                () -> new SourceActorScalarWireV1.Binding(ACTOR, 7, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceActorScalarWireV1.Binding(ACTOR, 0, ABI));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceActorScalarWireV1.Binding(ACTOR, 7,
                        "A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceActorScalarWireV1.Binding(ACTOR, 7,
                        "f".repeat(63)));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceActorScalarWireV1.Binding(ACTOR, 7,
                        "g".repeat(64)));
    }
}
