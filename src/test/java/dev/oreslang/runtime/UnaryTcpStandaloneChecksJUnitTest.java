package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the dependency-light unary transport proof kernels under the ordinary
 * Maven/JUnit gate so reviewed private carriers do not need shell execution.
 */
@Timeout(60)
final class UnaryTcpStandaloneChecksJUnitTest {
    @Test
    void sessionBudgetsDeadlinesReplayAndCancellationRaces() throws Exception {
        UnaryTcpSessionAdapterCheck.main(new String[0]);
    }

    @Test
    void fullMaximumPayloadLoopback() throws Exception {
        UnaryTcpMaxPayloadLoopbackCheck.main(new String[0]);
    }

    @Test
    void mutualTlsBoundaryRejectsPlaintextAndInvalidBindings() throws Exception {
        UnaryTcpMutualTlsAuthenticatorCheck.main(new String[0]);
    }
}
