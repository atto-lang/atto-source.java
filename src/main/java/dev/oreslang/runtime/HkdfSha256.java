package dev.oreslang.runtime;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF-SHA-256 over exact byte strings, with explicit CPU/memory limits. */
public final class HkdfSha256 {
    private static final int HASH_BYTES = 32;
    private static final int MAX_IKM_BYTES = 1024 * 1024;
    private static final int MAX_SALT_BYTES = 64 * 1024;
    private static final int MAX_INFO_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 255 * HASH_BYTES;

    private HkdfSha256() { }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int outputBytes) {
        check(ikm, 1, MAX_IKM_BYTES, "HKDF input key material");
        check(salt, 0, MAX_SALT_BYTES, "HKDF salt");
        check(info, 0, MAX_INFO_BYTES, "HKDF info");
        if (outputBytes < 1 || outputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("HKDF output must be 1..8160 bytes");
        }

        byte[] effectiveSalt = salt.length == 0 ? new byte[HASH_BYTES] : salt.clone();
        byte[] prk = null;
        byte[] previous = new byte[0];
        byte[] output = new byte[outputBytes];
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
            prk = mac.doFinal(ikm);
            int cursor = 0;
            for (int counter = 1; cursor < outputBytes; counter++) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                byte[] next = mac.doFinal();
                Arrays.fill(previous, (byte) 0);
                previous = next;
                int length = Math.min(next.length, outputBytes - cursor);
                System.arraycopy(next, 0, output, cursor, length);
                cursor += length;
            }
            return output;
        } catch (GeneralSecurityException unavailable) {
            Arrays.fill(output, (byte) 0);
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        } finally {
            Arrays.fill(effectiveSalt, (byte) 0);
            if (prk != null) Arrays.fill(prk, (byte) 0);
            Arrays.fill(previous, (byte) 0);
        }
    }

    private static void check(byte[] data, int minimum, int maximum, String name) {
        if (data == null || data.length < minimum || data.length > maximum) {
            throw new IllegalArgumentException("invalid " + name + " length");
        }
    }
}
