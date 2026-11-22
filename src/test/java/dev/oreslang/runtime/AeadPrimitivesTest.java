package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AeadPrimitivesTest {
    @Test
    void aesAndChaChaRoundTripAndUseUniqueNonces() {
        for (int algorithm : new int[] {
                AeadPrimitives.AES_256_GCM, AeadPrimitives.CHACHA20_POLY1305 }) {
            try (AeadPrimitives.Key key = AeadPrimitives.generateKey(algorithm)) {
                byte[] aad = "metadata".getBytes(StandardCharsets.UTF_8);
                byte[] plaintext = "secret message".getBytes(StandardCharsets.UTF_8);
                Set<String> nonces = new HashSet<>();
                for (int i = 0; i < 128; i++) {
                    AeadPrimitives.Sealed sealed = key.seal(aad, plaintext);
                    assertEquals(12, sealed.nonce().length);
                    assertEquals(plaintext.length + 16, sealed.ciphertext().length);
                    assertTrue(nonces.add(Arrays.toString(sealed.nonce())));
                    assertArrayEquals(plaintext, key.open(sealed.nonce(), aad, sealed.ciphertext()));
                }
                AeadPrimitives.Sealed empty = key.seal(new byte[0], new byte[0]);
                assertEquals(16, empty.ciphertext().length);
                assertArrayEquals(new byte[0], key.open(empty.nonce(), new byte[0], empty.ciphertext()));
            }
        }
    }

    @Test
    void tamperingWrongAadWrongKeyAndWrongNonceFailUniformly() {
        for (int algorithm : new int[] {
                AeadPrimitives.AES_256_GCM, AeadPrimitives.CHACHA20_POLY1305 }) {
            try (AeadPrimitives.Key key = AeadPrimitives.generateKey(algorithm);
                    AeadPrimitives.Key other = AeadPrimitives.generateKey(algorithm)) {
                byte[] aad = new byte[] { 1, 2, 3 };
                AeadPrimitives.Sealed sealed = key.seal(aad, new byte[] { 4, 5 });
                byte[] mutated = sealed.ciphertext();
                mutated[0] ^= 1;
                assertEquals("authentication failed", assertThrows(
                        AeadPrimitives.AuthenticationException.class,
                        () -> key.open(sealed.nonce(), aad, mutated)).getMessage());
                assertThrows(AeadPrimitives.AuthenticationException.class,
                        () -> key.open(sealed.nonce(), new byte[] { 2 }, sealed.ciphertext()));
                assertThrows(AeadPrimitives.AuthenticationException.class,
                        () -> other.open(sealed.nonce(), aad, sealed.ciphertext()));
                byte[] wrongNonce = sealed.nonce();
                wrongNonce[11] ^= 1;
                assertThrows(AeadPrimitives.AuthenticationException.class,
                        () -> key.open(wrongNonce, aad, sealed.ciphertext()));
                byte[] mutable = sealed.ciphertext();
                mutable[0] ^= 1;
                assertArrayEquals(new byte[] { 4, 5 },
                        key.open(sealed.nonce(), aad, sealed.ciphertext()));
            }
        }
    }

    @Test
    void rejectsMalformedLengthsAndClosesKey() {
        assertThrows(IllegalArgumentException.class, () -> AeadPrimitives.generateKey(0));
        AeadPrimitives.Key key = AeadPrimitives.generateKey(AeadPrimitives.AES_256_GCM);
        try {
            assertThrows(IllegalArgumentException.class, () -> key.seal(null, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> key.seal(new byte[0], null));
            assertThrows(IllegalArgumentException.class,
                    () -> key.seal(new byte[65537], new byte[0]));
            assertThrows(IllegalArgumentException.class,
                    () -> key.open(new byte[11], new byte[0], new byte[16]));
            assertThrows(IllegalArgumentException.class,
                    () -> key.open(new byte[12], new byte[0], new byte[15]));
        } finally {
            key.close();
            key.close();
        }
        assertThrows(IllegalStateException.class, () -> key.seal(new byte[0], new byte[0]));
        assertThrows(IllegalStateException.class,
                () -> key.open(new byte[12], new byte[0], new byte[16]));
    }
}
