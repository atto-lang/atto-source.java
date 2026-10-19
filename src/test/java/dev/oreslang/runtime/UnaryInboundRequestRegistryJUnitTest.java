package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Inbound peer-ID replay fencing and exact byte-credit reclamation. */
class UnaryInboundRequestRegistryJUnitTest {
    @Test @Timeout(25)
    void inboundReplayAndTerminalLifecycle() throws Exception {
        UnaryInboundRequestRegistryCheck.main(new String[0]);
    }
}
