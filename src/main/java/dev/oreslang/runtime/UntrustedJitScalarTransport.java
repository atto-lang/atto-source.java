package dev.oreslang.runtime;

/**
 * Common data-only host/guest admission for a pooled untrusted isolate.
 * No Java object identities, handles, byte buffers or native pointers pass.
 * Strings are bounded by their actual UTF-8 wire size, not UTF-16 code units.
 */
public final class UntrustedJitScalarTransport {
    private static final int MAX_VALUE_BYTES = 16 * 1024;
    private UntrustedJitScalarTransport() {}

    public static Object copyScalar(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) return value;
        if (value instanceof String text) {
            validateUtf8(text);
            return text;
        }
        if (value instanceof Double number && Double.isFinite(number)) return number;
        throw new SecurityException(
                "untrusted isolate transport permits only bounded UTF-8 strings, "
                + "integers, finite doubles, bool or null");
    }

    private static void validateUtf8(String text) {
        // Each UTF-16 code unit consumes at least one UTF-8 byte. Rejecting
        // oversized code-unit lengths first avoids unbounded scanning of
        // attacker-controlled strings, then count bytes without allocating.
        if (text.length() > MAX_VALUE_BYTES) {
            throw new SecurityException("untrusted isolate string exceeds UTF-8 byte quota");
        }
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new SecurityException("untrusted isolate rejects malformed UTF-16");
                }
                i++;
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new SecurityException("untrusted isolate rejects malformed UTF-16");
            } else if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_VALUE_BYTES) {
                throw new SecurityException("untrusted isolate string exceeds UTF-8 byte quota");
            }
        }
    }
}
