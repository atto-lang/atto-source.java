package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

/** Ensure Maven/JUnit executes the exact standalone JDK21 property fixture. */
class SourceTelemetryLiteralV1Test {
    @Test
    void decodedSpanAndLogLiteralsRemainBoundedAndUnicodeSafe() {
        SourceTelemetryLiteralV1StandaloneTest.main(new String[0]);
    }
}
