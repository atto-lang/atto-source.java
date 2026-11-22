package dev.oreslang.runtime;

/**
 * Immutable pointer-free W3C Trace Context identifiers.
 *
 * This is the host/runtime transport substrate, NOT the public AttoLang core
 * module, an OpenTelemetry SDK, a sampler, a security credential or an exporter.
 * It can be carried by logical continuations and actor envelopes without a
 * reference to a producer's heap, active Span or scheduler carrier.
 *
 * The first slice supports canonical traceparent version 00 only. Reject
 * unknown versions until their parsing rules are implemented and tested.
 */
public record TraceContextV1(long traceHigh, long traceLow, long spanId, int flags) {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public TraceContextV1 {
        if ((traceHigh | traceLow) == 0 || spanId == 0) {
            throw new IllegalArgumentException("trace and span identifiers must be nonzero");
        }
        if (flags < 0 || flags > 255) {
            throw new IllegalArgumentException("trace flags must be one byte");
        }
    }

    public boolean sampled() {
        return (flags & 1) != 0;
    }

    /**
     * Parse exactly 55 ASCII characters: 00-<32hex>-<16hex>-<2hex>.
     * Never consult a global tracer, user handler or process environment.
     */
    public static TraceContextV1 parseTraceparent(String header) {
        if (header == null || header.length() != 55
                || header.charAt(2) != '-' || header.charAt(35) != '-'
                || header.charAt(52) != '-') {
            throw new IllegalArgumentException("invalid traceparent length or separators");
        }
        if (header.charAt(0) != '0' || header.charAt(1) != '0') {
            throw new IllegalArgumentException("unsupported traceparent version");
        }
        long high = hex(header, 3, 16);
        long low = hex(header, 19, 16);
        long parent = hex(header, 36, 16);
        int flags = (int) hex(header, 53, 2);
        return new TraceContextV1(high, low, parent, flags);
    }

    /** Canonical ASCII lower-case v00 serialization. */
    public String traceparent() {
        char[] text = new char[55];
        text[0] = '0';
        text[1] = '0';
        text[2] = '-';
        putHex(traceHigh, text, 3, 16);
        putHex(traceLow, text, 19, 16);
        text[35] = '-';
        putHex(spanId, text, 36, 16);
        text[52] = '-';
        putHex(flags, text, 53, 2);
        return new String(text);
    }

    private static long hex(String source, int from, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) {
            char ch = source.charAt(from + i);
            int digit = ch >= '0' && ch <= '9' ? ch - '0'
                    : ch >= 'a' && ch <= 'f' ? ch - 'a' + 10
                    : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("traceparent contains non-ASCII hexadecimal");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static void putHex(long value, char[] dst, int from, int count) {
        for (int i = count - 1; i >= 0; i--) {
            dst[from + i] = HEX[(int) (value & 15)];
            value >>>= 4;
        }
    }
}
