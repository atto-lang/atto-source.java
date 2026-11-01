package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class CryptoPrimitivesTest {
    @Test
    void standardShaHmacAndPbkdf2VectorsMatch() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HexFormat.of().formatHex(CryptoPrimitives.sha256(
                        "abc".getBytes(StandardCharsets.UTF_8))));
        assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                HexFormat.of().formatHex(CryptoPrimitives.hmacSha256(
                        "key".getBytes(StandardCharsets.UTF_8),
                        "The quick brown fox jumps over the lazy dog"
                                .getBytes(StandardCharsets.UTF_8))));
        assertEquals(
                "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
                HexFormat.of().formatHex(CryptoPrimitives.pbkdf2HmacSha256(
                        "password".getBytes(StandardCharsets.UTF_8),
                        "salt".getBytes(StandardCharsets.UTF_8),
                        4096,
                        32)));
    }

    @Test
    void base64IsCanonicalAndSecureRandomIsBounded() {
        byte[] bytes = new byte[] {0, 1, 2, (byte) 0xff};
        String encoded = CryptoPrimitives.base64Encode(bytes);
        assertEquals("AAEC/w==", encoded);
        assertArrayEquals(bytes, CryptoPrimitives.base64Decode(encoded));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoPrimitives.base64Decode("AAEC/w==\n"));
        assertEquals(32, CryptoPrimitives.secureRandomBytes(32).length);
        assertThrows(IllegalArgumentException.class,
                () -> CryptoPrimitives.secureRandomBytes(0));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoPrimitives.secureRandomBytes(
                        CryptoPrimitives.MAX_RANDOM_BYTES + 1));
    }

    @Test
    void constantTimeEqualityAndPbkdf2BudgetsFailClosed() {
        assertTrue(CryptoPrimitives.constantTimeEquals(
                new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
        assertFalse(CryptoPrimitives.constantTimeEquals(
                new byte[] {1, 2, 3}, new byte[] {1, 2, 4}));
        assertFalse(CryptoPrimitives.constantTimeEquals(
                new byte[] {1, 2, 3}, new byte[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoPrimitives.pbkdf2HmacSha256(
                        new byte[] {1},
                        new byte[] {2},
                        CryptoPrimitives.MAX_PBKDF2_ITERATIONS + 1,
                        32));
        assertThrows(IllegalArgumentException.class,
                () -> CryptoPrimitives.pbkdf2HmacSha256(
                        new byte[] {1}, new byte[] {2}, 1, 0));
    }
}
