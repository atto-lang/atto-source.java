package dev.oreslang.dop;

import org.junit.jupiter.api.Test;

/** Maven CI uses the same JDK-only oracle that local smoke execution uses. */
final class SoaSnapshotTest {
    @Test
    void immutableRoundTripAndFailClosedAdmission() {
        SoaSnapshotSmoke.runAll();
    }
}
