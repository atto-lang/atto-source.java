package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SourceActorTypedWireV2Test {
    private static final String ABI = "0123456789abcdef".repeat(4);
    private static final UUID ACTOR =
            UUID.fromString("92098be7-bf7e-4d87-b1d0-1970b7cfe553");

    private static SourceActorTypedWireV2.Binding bind(
            ActorRuntime.ProxyWireScalarKind kind) {
        return new SourceActorTypedWireV2.Binding(
                new SourceActorScalarWireV1.Binding(ACTOR, 7, ABI), kind);
    }

    @Test
    void typedPrimitiveFramesRoundTripWithCanonicalHeaderAndPayload() {
        var integers = bind(ActorRuntime.ProxyWireScalarKind.INT);
        var booleans = bind(ActorRuntime.ProxyWireScalarKind.BOOL);
        var strings = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        int cap = 4096;

        for (long x : new long[] {
                0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE,
                0x0102030405060708L}) {
            byte[] frame = SourceActorTypedWireV2.encodeLong(integers, x, cap);
            assertEquals(SourceActorTypedWireV2.HEADER_BYTES + 8, frame.length);
            assertEquals(x, SourceActorTypedWireV2.decode(integers, frame));
        }
        assertEquals(true, SourceActorTypedWireV2.decode(
                booleans, SourceActorTypedWireV2.encodeBoolean(booleans, true, cap)));
        assertEquals(false, SourceActorTypedWireV2.decode(
                booleans, SourceActorTypedWireV2.encodeBoolean(booleans, false, cap)));
        for (String s : new String[] {
                "", "hello", "caf\u00e9", "emoji \ud83d\ude42", "\u0000\n\r",
                "\u6f22\u5b57", "e\u0301", "\u00e9"}) {
            byte[] frame = SourceActorTypedWireV2.encodeString(strings, s, cap);
            assertEquals(s, SourceActorTypedWireV2.decode(strings, frame));
        }
        assertArrayEquals(new byte[] {0x4f, 0x52, 0x4d, 0x32, 2, 3, 0, 0},
                Arrays.copyOf(
                        SourceActorTypedWireV2.encodeString(strings, "x", cap), 8));
    }

    @Test
    void everyHeaderBitMustFailBeforeAnyActorCouldReceive() {
        var binding = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        byte[] good = SourceActorTypedWireV2.encodeString(binding, "hi", 512);
        for (int at = 0; at < SourceActorTypedWireV2.HEADER_BYTES; ++at) {
            for (int bit = 0; bit < 8; ++bit) {
                byte[] bad = good.clone();
                bad[at] ^= (byte) (1 << bit);
                assertThrows(SecurityException.class,
                        () -> SourceActorTypedWireV2.decode(binding, bad),
                        "header corruption must fail at byte " + at
                                + " bit " + bit);
            }
        }
        for (int n : new int[] {0, 7, 8, 32, 67, 68, 69, 71, 100}) {
            byte[] malformed = Arrays.copyOf(good, n);
            if (n != good.length)
                assertThrows(SecurityException.class,
                        () -> SourceActorTypedWireV2.decode(binding, malformed));
        }
    }

    @Test
    void wrongPrimitiveKindForeignActorAndInvalidBooleanAreRejected() {
        int max = 512;
        var text = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        var truth = bind(ActorRuntime.ProxyWireScalarKind.BOOL);
        var integer = bind(ActorRuntime.ProxyWireScalarKind.INT);
        byte[] yes = SourceActorTypedWireV2.encodeBoolean(truth, true, max);
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(integer, yes));
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(text, yes));
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.encodeBoolean(text, true, max));
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.encodeString(integer, "x", max));
        byte[] invalidBool = yes.clone();
        invalidBool[SourceActorTypedWireV2.HEADER_BYTES] = 2;
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(truth, invalidBool));
        var foreign = new SourceActorTypedWireV2.Binding(
                new SourceActorScalarWireV1.Binding(
                        UUID.randomUUID(), 7, ABI),
                ActorRuntime.ProxyWireScalarKind.BOOL);
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(foreign, yes));
        var differentAbi = new SourceActorTypedWireV2.Binding(
                new SourceActorScalarWireV1.Binding(ACTOR, 7, "f".repeat(64)),
                ActorRuntime.ProxyWireScalarKind.BOOL);
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(differentAbi, yes));
        var differentIncarnation = new SourceActorTypedWireV2.Binding(
                new SourceActorScalarWireV1.Binding(ACTOR, 8, ABI),
                ActorRuntime.ProxyWireScalarKind.BOOL);
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(differentIncarnation, yes));
    }

    @Test
    void invalidUtf8UnpairedSurrogatesAndResourceOverflowFailClosed() {
        var text = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        byte[] valid = SourceActorTypedWireV2.encodeString(text, "ab", 512);
        byte[] invalidUtf8 = valid.clone();
        invalidUtf8[SourceActorTypedWireV2.HEADER_BYTES] = (byte) 0xc0;
        invalidUtf8[SourceActorTypedWireV2.HEADER_BYTES + 1] = (byte) 0xaf;
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(text, invalidUtf8));
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorTypedWireV2.encodeString(
                        text, new String(new char[] {(char) 0xd800}), 512));
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorTypedWireV2.encodeString(text, "too large", 69));
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorTypedWireV2.encodeLong(
                        bind(ActorRuntime.ProxyWireScalarKind.INT), 1, 69));
        // Huge caller budgets cannot bypass the absolute 1 MiB frame cap.
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorTypedWireV2.encodeString(
                        text, "x".repeat(SourceActorTypedWireV2.MAX_FRAME_BYTES),
                        Integer.MAX_VALUE));
        byte[] wrongLength = valid.clone();
        ByteBuffer.wrap(wrongLength).order(ByteOrder.BIG_ENDIAN)
                .putInt(64, Integer.MAX_VALUE);
        assertThrows(SecurityException.class,
                () -> SourceActorTypedWireV2.decode(text, wrongLength));
    }

    @Test
    void exactUnicodeFrameBoundariesRejectBeforePayloadAllocation() {
        var text = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        int h = SourceActorTypedWireV2.HEADER_BYTES;
        for (String unit : new String[] {"a", "\u00e9", "\u6f22", "\ud83d\ude42"}) {
            int unitBytes = unit.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8).length;
            String atLimit = unit.repeat(4);
            int cap = h + 4 * unitBytes;
            byte[] admitted = SourceActorTypedWireV2.encodeString(
                    text, atLimit, cap);
            assertEquals(cap, admitted.length,
                    "encoder must use exactly the configured byte capacity");
            assertEquals(atLimit,
                    SourceActorTypedWireV2.decode(text, admitted));
            assertThrows(IllegalArgumentException.class,
                    () -> SourceActorTypedWireV2.encodeString(
                            text, atLimit + unit, cap),
                    "a fifth multibyte value must not allocate past quota");
        }

        // A malformed UTF-16 String is not allowed to silently substitute a
        // replacement byte sequence; both surrogate orientations are denied.
        for (String malformed : new String[] {
                "\ud800", "\udc00", "abc\ud800x", "abc\udc00",
                "\ud800\ud800", "\udc00\ud800"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceActorTypedWireV2.encodeString(
                            text, malformed, h + 100));
        }

        int available = SourceActorTypedWireV2.MAX_FRAME_BYTES - h;
        String exceedsAbsoluteCap = "\u6f22".repeat(available / 3 + 1);
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorTypedWireV2.encodeString(
                        text, exceedsAbsoluteCap, Integer.MAX_VALUE),
                "large multibyte input must be rejected in length preflight");
        String fitsAbsoluteCap = "\u6f22".repeat(available / 3);
        byte[] largest = SourceActorTypedWireV2.encodeString(
                text, fitsAbsoluteCap, Integer.MAX_VALUE);
        assertEquals(h + fitsAbsoluteCap.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length,
                largest.length);
        assertEquals(fitsAbsoluteCap,
                SourceActorTypedWireV2.decode(text, largest));
    }

    @Test
    void deterministicRoundTripsPreserveAllSupportedPrimitiveValues() {
        var integers = bind(ActorRuntime.ProxyWireScalarKind.INT);
        var booleans = bind(ActorRuntime.ProxyWireScalarKind.BOOL);
        var strings = bind(ActorRuntime.ProxyWireScalarKind.STRING);
        Random random = new Random(0x4f524d32L);
        for (int i = 0; i < 20_000; ++i) {
            long n = random.nextLong();
            assertEquals(n, SourceActorTypedWireV2.decode(integers,
                    SourceActorTypedWireV2.encodeLong(integers, n, 128)));
            boolean v = random.nextBoolean();
            assertEquals(v, SourceActorTypedWireV2.decode(booleans,
                    SourceActorTypedWireV2.encodeBoolean(booleans, v, 128)));
            String value = "v" + Long.toHexString(random.nextLong());
            assertEquals(value, SourceActorTypedWireV2.decode(strings,
                    SourceActorTypedWireV2.encodeString(strings, value, 128)));
        }
    }
}
