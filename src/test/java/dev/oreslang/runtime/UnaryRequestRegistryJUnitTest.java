package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Bounded per-session admission and exact credit reclamation under races. */
class UnaryRequestRegistryJUnitTest {
    @Test @Timeout(25)
    void quotaReplayAndTerminalLifecycle() throws Exception {
        UnaryRequestRegistryCheck.main(new String[0]);
    }
}
