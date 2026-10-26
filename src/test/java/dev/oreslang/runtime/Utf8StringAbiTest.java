package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class Utf8StringAbiTest {
    @Test
    void ordinaryStringUsesExactUtf8ExtentAndKeepsEmbeddedNul() {
        String value = "A" + (char) 0 + "Bé😀";
        Utf8StringAbi.Utf8Bytes encoded = Utf8StringAbi.encode(value);

        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, encoded.bytes());
        assertEquals(expected.length, encoded.byteLength());
        assertEquals(value, Utf8StringAbi.decode(encoded.bytes(), encoded.byteLength()));
    }

    @Test
    void explicitLengthControlsInboundDecodeInsteadOfSentinelScanning() {
        byte[] storage = new byte[] {'A', 0, 'B', 'Z', 'Z'};
        assertEquals("A" + (char) 0 + "B", Utf8StringAbi.decode(storage, 3));
    }

    @Test
    void cStringIsDistinctAndLengthExcludesTerminator() {
        Utf8StringAbi.CString value = Utf8StringAbi.toCString("é😀");
        byte[] utf8 = "é😀".getBytes(StandardCharsets.UTF_8);

        assertEquals(utf8.length, value.byteLength());
        assertEquals(utf8.length + 1, value.storage().length);
        assertEquals(0, value.storage()[value.byteLength()]);
        assertArrayEquals(utf8, java.util.Arrays.copyOf(value.storage(), value.byteLength()));
        assertEquals("é😀", Utf8StringAbi.fromCString(value));
    }

    @Test
    void cStringRejectsEmbeddedNulInsteadOfTruncating() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Utf8StringAbi.toCString("A" + (char) 0 + "B"));
        assertTrue(failure.getMessage().contains("embedded NUL"));
    }

    @Test
    void malformedUtf8AndUnpairedSurrogatesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Utf8StringAbi.decode(new byte[] {(byte) 0xc0, (byte) 0xaf}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> Utf8StringAbi.encode(String.valueOf((char) 0xd800)));
        assertThrows(IllegalArgumentException.class,
                () -> Utf8StringAbi.encode(String.valueOf((char) 0xdc00)));
    }

    @Test
    void cStringConstructorRejectsForgedExtentAndPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> new Utf8StringAbi.CString(new byte[] {'x'}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Utf8StringAbi.CString(new byte[] {'A', 0, 'B', 0}, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Utf8StringAbi.CString(new byte[] {(byte) 0xc0, (byte) 0xaf, 0}, 2));
    }
}
