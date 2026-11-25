package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * JUnit entrypoint for host-only nonfatal CONTROL contract probes.
 * Real actor dispatch and atomic code publication require integration tests.
 */
@Timeout(30)
final class OtpSupervisorCommandLaneV1Test {
    @Test
    void leasesCapacityEpochFencingAndConcurrentRollbackArbitration() throws Exception {
        OtpSupervisorCommandLaneV1Smoke.main(new String[0]);
    }
}
