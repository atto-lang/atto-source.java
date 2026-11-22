package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Direct JCA provider known-answer tests. Fixed raw keys are test-only and
 * cannot enter the guest's generated-only opaque AEAD key API.
 */
final class CryptoProviderKnownAnswerTest {
    @Test
    void aes256GcmZeroKeyNistTag() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b",
                HexFormat.of().formatHex(cipher.doFinal(new byte[0])));
    }

    @Test
    void ietfChaCha20Poly1305ZeroKeyTag() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                    new IvParameterSpec(nonce));
        assertEquals("4eb972c9a8fb3a1b382bb4d36f5ffad1",
                HexFormat.of().formatHex(cipher.doFinal(new byte[0])));
    }
}
