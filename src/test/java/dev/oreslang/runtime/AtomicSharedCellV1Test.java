package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** JUnit host-runtime probe. Not a compiled AttoLang source / Native Image test. */
@Timeout(30)
class AtomicSharedCellV1Test {
    @Test
    void lifecycleAndStrongWeakRace() throws Exception {
        assertDoesNotThrow(() -> AtomicSharedCellV1HostProbe.main(new String[0]));
    }
}
