package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real-loopback authenticated unary TCP admission/lifecycle regression. */
final class UnaryTcpSessionAdapterJUnitTest {
    @Test
    @Timeout(30)
    void authenticatedSessionBudgetsDeadlinesAndTerminalRaces() throws Exception {
        UnaryTcpSessionAdapterCheck.main(new String[0]);
    }
}
