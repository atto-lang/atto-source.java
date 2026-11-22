package dev.oreslang.runtime;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Trusted host primitives for a future std.crypto AEAD binding.
 *
 * <p>Keys are freshly generated, never imported/exported, and managed by opaque
 * closeable handles. Each handle has a nonce prefix and a monotonically
 * increasing 64-bit counter, so this host cannot reuse a nonce with that key.
 * These primitives do not grant guest access and do not replace capability,
 * ownership, or quota checks in a future guest binding.
 */
public final class AeadPrimitives {
    public static final int AES_256_GCM = 1;
    public static final int CHACHA20_POLY1305 = 2;
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_AAD_BYTES = 64 * 1024;
    // Conservative per-key AEAD invocation cap. Rotate keys before exhaustion.
    public static final long MAX_ENCRYPTIONS_PER_KEY = 1L << 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AeadPrimitives() { }

    public static Key generateKey(int algorithm) {
        checkAlgorithm(algorithm);
        byte[] key = new byte[KEY_BYTES];
        byte[] prefix = new byte[4];
        RANDOM.nextBytes(key);
        RANDOM.nextBytes(prefix);
        return new Key(algorithm, key, prefix);
    }

    private static void checkAlgorithm(int algorithm) {
        if (algorithm != AES_256_GCM && algorithm != CHACHA20_POLY1305) {
            throw new IllegalArgumentException("unsupported AEAD algorithm");
        }
    }

    private static void checkLength(byte[] input, int minimum, int maximum, String what) {
        if (input == null || input.length < minimum || input.length > maximum) {
            throw new IllegalArgumentException("invalid " + what + " size");
        }
    }

    private static Cipher cipher(int mode, int algorithm, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        if (algorithm == AES_256_GCM) {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c;
        }
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        return c;
    }

    /** Immutable result with defensive copying; ciphertext includes the full tag. */
    public static final class Sealed {
        private final byte[] nonce;
        private final byte[] ciphertext;

        private Sealed(byte[] nonce, byte[] ciphertext) {
            this.nonce = nonce.clone();
            this.ciphertext = ciphertext.clone();
        }

        public byte[] nonce() { return nonce.clone(); }

        public byte[] ciphertext() { return ciphertext.clone(); }
    }

    /** Uniform decryption failure for tampering, wrong key, nonce, or AAD. */
    public static final class AuthenticationException extends RuntimeException {
        public AuthenticationException() { super("authentication failed"); }
    }

    public static final class Key implements AutoCloseable {
        private final int algorithm;
        private final byte[] prefix;
        private final byte[] material;
        private long nextCounter;
        private boolean exhausted;
        private boolean closed;

        private Key(int algorithm, byte[] material, byte[] prefix) {
            this.algorithm = algorithm;
            this.material = material;
            this.prefix = prefix;
        }

        public int algorithm() { return algorithm; }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("AEAD key is closed");
        }

        private byte[] reserveNonce() {
            if (exhausted || nextCounter >= MAX_ENCRYPTIONS_PER_KEY) {
                throw new IllegalStateException("AEAD per-key invocation limit exhausted");
            }
            long n = nextCounter++;
            if (nextCounter >= MAX_ENCRYPTIONS_PER_KEY) exhausted = true;
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(prefix, 0, nonce, 0, prefix.length);
            for (int i = 0; i < 8; i++) {
                nonce[11 - i] = (byte) (n >>> (8 * i));
            }
            return nonce;
        }

        /** Sequentializes counter reservation, closure and encryption. */
        public synchronized Sealed seal(byte[] aad, byte[] plaintext) {
            ensureOpen();
            checkLength(aad, 0, MAX_AAD_BYTES, "AAD");
            checkLength(plaintext, 0, MAX_MESSAGE_BYTES, "plaintext");
            byte[] nonce = reserveNonce();
            byte[] input = plaintext.clone();
            try {
                Cipher c = cipher(Cipher.ENCRYPT_MODE, algorithm, material, nonce);
                c.updateAAD(aad.clone());
                return new Sealed(nonce, c.doFinal(input));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AEAD encryption unavailable", e);
            } finally {
                Arrays.fill(input, (byte) 0);
            }
        }

        /** Releases plaintext only after the AEAD authentication tag verifies. */
        public synchronized byte[] open(byte[] nonce, byte[] aad, byte[] ciphertext) {
            ensureOpen();
            checkLength(nonce, NONCE_BYTES, NONCE_BYTES, "nonce");
            checkLength(aad, 0, MAX_AAD_BYTES, "AAD");
            checkLength(ciphertext, TAG_BYTES, MAX_MESSAGE_BYTES + TAG_BYTES,
                    "ciphertext and tag");
            byte[] input = ciphertext.clone();
            try {
                Cipher c = cipher(Cipher.DECRYPT_MODE, algorithm, material, nonce.clone());
                c.updateAAD(aad.clone());
                return c.doFinal(input);
            } catch (AEADBadTagException e) {
                throw new AuthenticationException();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AEAD decryption unavailable", e);
            } finally {
                Arrays.fill(input, (byte) 0);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            Arrays.fill(material, (byte) 0);
            Arrays.fill(prefix, (byte) 0);
        }
    }
}
