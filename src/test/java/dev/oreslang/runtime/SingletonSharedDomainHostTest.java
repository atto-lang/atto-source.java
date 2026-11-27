package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the same real-Java host smoke as the private-carrier javac/java gate.
 * Does not certify guest lowering or sharing between native isolates.
 */
final class SingletonSharedDomainHostTest {
    @Test
    @Timeout(30)
    void runtimeOwnedSharedDomainAndConcurrentRwHostContract() throws Exception {
        SingletonSharedDomainHostSmoke.main(new String[0]);
    }
}
