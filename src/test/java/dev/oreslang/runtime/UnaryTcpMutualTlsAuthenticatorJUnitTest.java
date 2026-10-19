package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Fail-closed unary TCP mTLS configuration regression. */
final class UnaryTcpMutualTlsAuthenticatorJUnitTest {
    @Test
    @Timeout(10)
    void rejectsPlaintextInvalidBindingsAndMissingAuthorization() throws Exception {
        UnaryTcpMutualTlsAuthenticatorCheck.main(new String[0]);
    }
}
