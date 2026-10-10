package dev.oreslang.runtime;

/**
 * Common data-only host/guest admission for a pooled untrusted isolate.
 * No Java object identities, handles, byte buffers or native pointers pass.
 */
public final class UntrustedJitScalarTransport {
    private static final int MAX_VALUE_CHARS = 16 * 1024;
    private UntrustedJitScalarTransport() {}

    public static Object copyScalar(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) return value;
        if (value instanceof String text && text.length() <= MAX_VALUE_CHARS) return text;
        if (value instanceof Double number && Double.isFinite(number)) return number;
        throw new SecurityException(
                "untrusted isolate transport permits only bounded strings, integers, finite doubles, bool or null");
    }
}
