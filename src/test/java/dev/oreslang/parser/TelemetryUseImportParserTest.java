package dev.oreslang.parser;

import org.junit.jupiter.api.Test;

/**
 * Parser admission and lexical capture contract.
 * Does not claim trusted std.telemetry runtime or guest instrumentation.
 */
final class TelemetryUseImportParserTest {
    @Test
    void canonicalTelemetryUseAndCaptureAreFailClosed() {
        TelemetryUseSourceSmoke.main(new String[0]);
    }
}
