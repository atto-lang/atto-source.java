package dev.oreslang;

import org.junit.jupiter.api.Test;

/** JUnit entrypoint for the host-only scheduler-substrate stress probe. */
final class CriticalSectionGateJUnitTest {
    @Test
    void queueCancellationRetirementAndPhysicalCompletion() throws Exception {
        CriticalSectionGateStandaloneTest.main(new String[0]);
    }
}
