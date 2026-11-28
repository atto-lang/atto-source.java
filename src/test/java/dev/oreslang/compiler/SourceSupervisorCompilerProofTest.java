package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Includes the exact-source compiler decision-plan proof in Maven CI. */
@Timeout(15)
final class SourceSupervisorCompilerProofTest {
    @Test
    void checkedStopOnlyGuestSourceIsLoweredAndUnsupportedEffectsDenied() {
        SourceSupervisorCompilerProof.runAll();
    }
}
