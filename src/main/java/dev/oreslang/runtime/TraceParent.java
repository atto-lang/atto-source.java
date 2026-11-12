package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, pointer-free W3C Trace Context version 00 header primitive.
 * Context propagation across actor/task envelopes is NOT implemented here.
 * Only trusted HTTP boundary code may parse/inject these values, after
 * authorization and tenant filtering. This is not an exporter or guest API.
 *
 * IDs are precisely 16 and 8 cryptographically random bytes. No baggage,
 * raw request headers, path, body, authorization or guest-owned pointers.
 */
public record TraceParent(String traceId, String spanId, int flags) {
    private static final int HEADER_LENGTH = 55;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public TraceParent {
        if (!validHex(traceId, 32) || allZero(traceId)
                || !validHex(spanId, 16) || allZero(spanId)
                || flags < 0 || flags > 255) {
            throw new IllegalArgumentException("invalid W3C TraceParent fields");
        }
    }

    /** Strict v00 admission. Reject duplicate/unknown versions at HTTP layer. */
    public static Optional<TraceParent> parse(String raw) {
        if (raw == null || raw.length() != HEADER_LENGTH
                || raw.charAt(0) != '0' || raw.charAt(1) != '0'
                || raw.charAt(2) != '-' || raw.charAt(35) != '-'
                || raw.charAt(52) != '-') {
            return Optional.empty();
        }
        String trace = raw.substring(3, 35);
        String span = raw.substring(36, 52);
        char a = raw.charAt(53), b = raw.charAt(54);
        if (!validHex(trace, 32) || allZero(trace)
                || !validHex(span, 16) || allZero(span)
                || !isLowerHex(a) || !isLowerHex(b)) {
            return Optional.empty();
        }
        int bits = Character.digit(a, 16) * 16 + Character.digit(b, 16);
        return Optional.of(new TraceParent(trace, span, bits));
    }

    /** A sampled or nonrecording root context, without span/event creation. */
    public static TraceParent newRoot(boolean sampled) {
        return new TraceParent(randomHex(16), randomHex(8), sampled ? 1 : 0);
    }

    /** Retains distributed trace ID; creates a distinct outbound child ID. */
    public TraceParent newChild() {
        return new TraceParent(traceId, randomHex(8), flags);
    }

    public boolean sampled() {
        return (flags & 1) == 1;
    }

    public String toHeader() {
        return "00-" + traceId + "-" + spanId
                + "-" + HEX[(flags >>> 4) & 0xf] + HEX[flags & 0xf];
    }

    private static String randomHex(int bytes) {
        byte[] entropy = new byte[bytes];
        for (int attempt = 0; attempt < 16; attempt++) {
            RANDOM.nextBytes(entropy);
            boolean anyNonzero = false;
            for (byte b : entropy) anyNonzero |= b != 0;
            if (!anyNonzero) continue;
            char[] chars = new char[bytes * 2];
            for (int i = 0; i < bytes; i++) {
                int unsigned = entropy[i] & 0xff;
                chars[i * 2] = HEX[unsigned >>> 4];
                chars[i * 2 + 1] = HEX[unsigned & 15];
            }
            java.util.Arrays.fill(entropy, (byte) 0);
            return new String(chars);
        }
        java.util.Arrays.fill(entropy, (byte) 0);
        throw new IllegalStateException("unable to mint nonzero secure trace ID");
    }

    private static boolean validHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!isLowerHex(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean allZero(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') return false;
        }
        return true;
    }

    private static boolean isLowerHex(char value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f');
    }
}
