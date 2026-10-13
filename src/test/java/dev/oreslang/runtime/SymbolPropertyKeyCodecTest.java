package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolPropertyKeyCodecTest {
    @Test void tagsKeepStringAndSymbolKeysNominallyDistinct() {
        Object text = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode("ready"));
        Object process = SymbolPropertyKeyCodec.decode(
                SymbolPropertyKeyCodec.encode(OresSymbol.process("ready")));
        assertEquals("ready", text);
        assertEquals(OresSymbol.process("ready"), process);
        assertNotEquals(text, process);
        assertArrayEquals(
                new byte[]{'O', 'K', 1, 0, 0, 3, 'f', 'o', 'o'},
                SymbolPropertyKeyCodec.encode("foo"));
        assertArrayEquals(
                new byte[]{'O', 'K', 1, 1, 0, 3, 'f', 'o', 'o'},
                SymbolPropertyKeyCodec.encode(OresSymbol.process("foo")));
        assertEquals("#foo", SymbolPropertyKeyCodec.decode(
                SymbolPropertyKeyCodec.encode("#foo")));
    }

    @Test void stableAndProcessSymbolsRemainSeparateAcrossRoundTrips() {
        String name = "550e8400-e29b-41d4-a716-446655440000";
        Object process = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(OresSymbol.process(name)));
        Object stable = SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(OresSymbol.stable(name)));
        assertNotEquals(process, stable);
        assertEquals(OresSymbol.stable(name), stable);
        assertNotSame(stable, SymbolPropertyKeyCodec.decode(SymbolPropertyKeyCodec.encode(stable)));
    }

    @Test void duplicatesAreRejectedButNamespacedKeysCanCoexist() {
        var keys = List.of(
                SymbolPropertyKeyCodec.encode("foo"),
                SymbolPropertyKeyCodec.encode(OresSymbol.process("foo")));
        assertDoesNotThrow(() -> SymbolPropertyKeyCodec.requireDistinct(keys));
        assertThrows(IllegalArgumentException.class, () ->
                SymbolPropertyKeyCodec.requireDistinct(List.of(
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("café")),
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("cafe\u0301")))));
        assertThrows(IllegalArgumentException.class, () ->
                SymbolPropertyKeyCodec.requireDistinct(List.of(
                        SymbolPropertyKeyCodec.encode("foo"),
                        SymbolPropertyKeyCodec.encode("foo"))));
    }

    @Test void wireDecoderRejectsMalformedLengthsKindsUtf8AndNfc() {
        byte[] valid = SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"));
        byte[] badKind = valid.clone();
        badKind[3] = (byte) 0x7f;
        byte[] badVersion = valid.clone();
        badVersion[2] = 2;
        byte[] badLength = valid.clone();
        badLength[5] = 2;
        byte[] extraByte = Arrays.copyOf(valid, valid.length + 1);
        byte[] badUtf8 = new byte[]{'O', 'K', 1, 1, 0, 2, (byte) 0xc0, (byte) 0x80};
        byte[] emptySymbol = new byte[]{'O', 'K', 1, 1, 0, 0};
        byte[] decomposedName = wire(1, "cafe\u0301".getBytes(StandardCharsets.UTF_8));
        for (byte[] bad : List.of(badKind, badVersion, badLength, extraByte,
                badUtf8, emptySymbol, decomposedName)) {
            assertThrows(IllegalArgumentException.class,
                    () -> SymbolPropertyKeyCodec.decode(bad),
                    Arrays.toString(bad));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.encode("\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.encode("x".repeat(16_385)));
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.decode(new byte[100_000]));
    }

    @Test void untrustedAdmissionRequiresExactScopedIdentity() {
        OresSymbol secret = OresSymbol.process("tenant-a/private");
        byte[] secretWire = SymbolPropertyKeyCodec.encode(secret);
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(secretWire));
        assertEquals(secret,
                SymbolPropertyKeyCodec.decodeForUntrusted(secretWire, Set.of(secret)));
        assertThrows(SecurityException.class,
                () -> SymbolPropertyKeyCodec.decodeForUntrusted(
                        secretWire, Set.of(OresSymbol.process("tenant-b/private"))));
        assertEquals(OresSymbol.process("ready"),
                SymbolPropertyKeyCodec.decodeForUntrusted(
                        SymbolPropertyKeyCodec.encode(OresSymbol.process("ready"))));
        assertEquals("tenant-a/private",
                SymbolPropertyKeyCodec.decodeForUntrusted(
                        SymbolPropertyKeyCodec.encode("tenant-a/private")));
    }

    @Test void entryCountQuotaIsEnforcedBeforeDecode() {
        List<byte[]> tooMany = new ArrayList<>();
        byte[] foo = SymbolPropertyKeyCodec.encode("foo");
        for (int i = 0; i <= SymbolPropertyKeyCodec.MAX_KEYS_PER_OBJECT; i++) {
            tooMany.add(foo);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SymbolPropertyKeyCodec.requireDistinct(tooMany));
    }

    private static byte[] wire(int kind, byte[] name) {
        return ByteBuffer.allocate(6 + name.length)
                .put((byte) 'O').put((byte) 'K').put((byte) 1)
                .put((byte) kind).putShort((short) name.length)
                .put(name).array();
    }
}
