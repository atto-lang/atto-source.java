package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Re-executes the SHA-mirrored production CONTROL proof under Maven/JUnit. */
@Timeout(25)
final class SupervisorStopOnlyControlProofTest {
    @Test
    void realStopOnlyHostControlContract() throws Exception {
        SupervisorStopOnlyControlProof.runAll();
    }
}
