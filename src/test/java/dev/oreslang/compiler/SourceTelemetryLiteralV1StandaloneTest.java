package dev.oreslang.compiler;

import java.nio.charset.StandardCharsets;

/** Standalone JDK21 compiler-literal test; does not require Maven or Truffle. */
public final class SourceTelemetryLiteralV1StandaloneTest {
    private SourceTelemetryLiteralV1StandaloneTest() { }

    private static void expect(boolean condition) {
        if (!condition) throw new AssertionError("source telemetry literal admission mismatch");
    }

    private static boolean permitted(String value, boolean span) {
        try {
            if (span) SourceTelemetryLiteralV1.requireSpanName(value);
            else SourceTelemetryLiteralV1.requireLogMessage(value);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static boolean oracle(String value, int byteLimit) {
        if (value == null || value.isEmpty() ||
                value.getBytes(StandardCharsets.UTF_8).length > byteLimit) return false;
        for (int cp : value.codePoints().toArray()) {
            if (Character.isISOControl(cp) || (cp >= 0xd800 && cp <= 0xdfff))
                return false;
        }
        return true;
    }

    public static void main(String[] args) {
        expect(permitted("hammer.started", false));
        expect(permitted("hammer", true));
        expect(permitted("x".repeat(96), false));
        expect(!permitted("x".repeat(97), false));
        expect(permitted("x".repeat(256), true));
        expect(!permitted("x".repeat(257), true));
        expect(permitted("before\u00a0after", false));
        expect(permitted("llama:\ud83e\udd99", true));
        expect(!permitted(null, false));
        expect(!permitted("", true));
        expect(!permitted("line\nname", true));
        expect(!permitted("nul\0inside", false));
        expect(!permitted("del\u007f", false));
        expect(!permitted("next\u0085line", false));
        expect(!permitted("next\u009fline", true));
        expect(!permitted(new String(new char[]{'x', (char) 0xd800}), false));
        expect(!permitted(new String(new char[]{(char) 0xdc00, 'x'}), true));

        long state = 0x9e3779b97f4a7c15L;
        for (int iteration = 0; iteration < 10000; ++iteration) {
            StringBuilder value = new StringBuilder();
            int n = (int) (state & 0x1ff);
            for (int i = 0; i < n; ++i) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                value.append((char) (state >>> 48));
            }
            String text = value.toString();
            expect(permitted(text, false) == oracle(text, 96));
            expect(permitted(text, true) == oracle(text, 256));
        }
        System.out.println("JAVA_STATIC_TELEMETRY_LITERAL_HOST_PASS:10000");
    }
}
