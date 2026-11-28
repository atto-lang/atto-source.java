package dev.oreslang.compiler;

import java.nio.charset.StandardCharsets;

/**
 * Compiler-only bounded, decoded source literals for static log sites and
 * lexical span names. No Truffle node, runtime probe or guest allocation.
 */
public final class SourceTelemetryLiteralV1 {
    private SourceTelemetryLiteralV1() { }

    public static void requireLogMessage(String text) {
        requireBounded(text, 96, "log.info");
    }

    public static void requireSpanName(String text) {
        requireBounded(text, 256, "telemetry.span");
    }

    private static void requireBounded(String text, int byteLimit, String operation) {
        if (text == null || text.isEmpty() ||
                text.getBytes(StandardCharsets.UTF_8).length > byteLimit) {
            throw new IllegalArgumentException(operation + " requires bounded source literal");
        }
        for (int i = 0; i < text.length(); ++i) {
            char code = text.charAt(i);
            if (Character.isISOControl(code)) {
                throw new IllegalArgumentException(
                        operation + " source literal contains control character");
            }
            if (Character.isHighSurrogate(code)) {
                if (i + 1 >= text.length() ||
                        !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            operation + " source literal contains unpaired surrogate");
                }
                ++i;
            } else if (Character.isLowSurrogate(code)) {
                throw new IllegalArgumentException(
                        operation + " source literal contains unpaired surrogate");
            }
        }
    }
}
