package dev.oreslang.types;

import org.junit.jupiter.api.Test;

/** Full Maven/JUnit wrapper for production scoped control-flow analyzer. */
final class TelemetryScopedControlFlowTest {
    @Test
    void telemetryAndBorrowedUsingHonorReturnFinallyGuards() {
        TelemetryScopedControlFlowSmoke.main(new String[0]);
    }
}
