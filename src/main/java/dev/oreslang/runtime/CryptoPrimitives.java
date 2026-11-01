package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded backend-neutral cryptographic byte primitives for guest protocols.
 *
 * <p>This API is deliberately data-only: no keys are persisted, no algorithm
 * names come from guest input, and SecureRandom cannot be seeded by guest code.
 * PostgreSQL SCRAM is one consumer; these primitives are not database-specific.
 */
public final class CryptoPrimitives {
    public static final int MAX_HASH_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_KEY_BYTES = 1024 * 1024;
    public static final int MAX_SALT_BYTES = 64 * 1024;
    public static final int MAX_PBKDF2_ITERATIONS = 100_000;
    public static final int MAX_DERIVED_BYTES = 1024;
    public static final int MAX_RANDOM_BYTES = 4096;
    public static final int MAX_BASE64_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_BASE64_DECODED_BYTES = 16 * 1024 * 1024;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoPrimitives() { }

    public static byte[] sha256(byte[] input) {
        checkedBytes(input, MAX_HASH_INPUT_BYTES, "SHA-256 input");
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        checkedBytes(key, MAX_KEY_BYTES, "HMAC key");
        checkedBytes(message, MAX_HASH_INPUT_BYTES, "HMAC message");
        try {
            Mac mac = hmac(key);
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    public static byte[] pbkdf2HmacSha256(
            byte[] password,
            byte[] salt,
            int iterations,
            int outputBytes) {
        checkedBytes(password, MAX_KEY_BYTES, "PBKDF2 password");
        checkedBytes(salt, MAX_SALT_BYTES, "PBKDF2 salt");
        if (iterations < 1 || iterations > MAX_PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException(
                    "PBKDF2 iterations must be 1.." + MAX_PBKDF2_ITERATIONS);
        }
        if (outputBytes < 1 || outputBytes > MAX_DERIVED_BYTES) {
            throw new IllegalArgumentException(
                    "PBKDF2 output bytes must be 1.." + MAX_DERIVED_BYTES);
        }

        try {
            Mac mac = hmac(password);
            int hashBytes = mac.getMacLength();
            int blocks = (outputBytes + hashBytes - 1) / hashBytes;
            ByteArrayOutputStream output = new ByteArrayOutputStream(blocks * hashBytes);
            for (int block = 1; block <= blocks; block++) {
                byte[] counter = new byte[] {
                        (byte) (block >>> 24),
                        (byte) (block >>> 16),
                        (byte) (block >>> 8),
                        (byte) block
                };
                mac.update(salt);
                byte[] u = mac.doFinal(counter);
                byte[] t = u.clone();
                try {
                    for (int round = 1; round < iterations; round++) {
                        byte[] next = mac.doFinal(u);
                        Arrays.fill(u, (byte) 0);
                        u = next;
                        for (int i = 0; i < t.length; i++) t[i] ^= u[i];
                    }
                    output.writeBytes(t);
                } finally {
                    Arrays.fill(u, (byte) 0);
                    Arrays.fill(t, (byte) 0);
                }
            }
            byte[] expanded = output.toByteArray();
            byte[] result = Arrays.copyOf(expanded, outputBytes);
            Arrays.fill(expanded, (byte) 0);
            return result;
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    public static String base64Encode(byte[] input) {
        checkedBytes(input, MAX_BASE64_INPUT_BYTES, "Base64 input");
        return Base64.getEncoder().encodeToString(input);
    }

    public static byte[] base64Decode(String encoded) {
        if (encoded == null) throw new NullPointerException("encoded");
        if (encoded.length() > (MAX_BASE64_DECODED_BYTES * 4L / 3L) + 8L) {
            throw new IllegalArgumentException("Base64 input exceeds bounded decoded budget");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("invalid canonical Base64", malformed);
        }
        if (decoded.length > MAX_BASE64_DECODED_BYTES) {
            throw new IllegalArgumentException("Base64 decoded output exceeds budget");
        }
        // PostgreSQL SCRAM uses canonical standard Base64 without whitespace.
        if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException("non-canonical Base64");
        }
        return decoded;
    }

    public static byte[] secureRandomBytes(int count) {
        if (count < 1 || count > MAX_RANDOM_BYTES) {
            throw new IllegalArgumentException(
                    "secure_random_bytes count must be 1.." + MAX_RANDOM_BYTES);
        }
        byte[] bytes = new byte[count];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        checkedBytes(left, MAX_HASH_INPUT_BYTES, "constant-time left input");
        checkedBytes(right, MAX_HASH_INPUT_BYTES, "constant-time right input");
        return MessageDigest.isEqual(left, right);
    }

    private static Mac hmac(byte[] key)
            throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac;
    }

    private static void checkedBytes(byte[] bytes, int maximum, String what) {
        if (bytes == null) throw new NullPointerException(what);
        if (bytes.length > maximum) {
            throw new IllegalArgumentException(what + " exceeds " + maximum + " bytes");
        }
    }
}
