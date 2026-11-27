package dev.oreslang;

import org.junit.jupiter.api.Test;

final class StateTerminalParityTest {
    @Test void drainReadersAndWriter() throws Exception {
        StateTerminalParityChecks.drainReadersAndWriter();
    }
    @Test void checkedFailureReleasesWriter() {
        StateTerminalParityChecks.checkedFailureReleasesWriter();
    }
    @Test void concurrentCloseNeverResurrects() throws Exception {
        StateTerminalParityChecks.concurrentCloseNeverResurrects();
    }
}
