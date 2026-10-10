package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class UntrustedJitCodeUnitTest {
    @Test void rejectsObjectsOrUnboundedPayloadsBeforeCrossingIsolate() {
        assertNull(UntrustedJitCodeUnit.copyScalar(null));
        assertEquals(12, UntrustedJitCodeUnit.copyScalar(12));
        assertEquals("allowed", UntrustedJitCodeUnit.copyScalar("allowed"));
        assertEquals(true, UntrustedJitCodeUnit.copyScalar(true));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(new Object()));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                new ActorGraphArena.NodeId(UUID.randomUUID(), 0, 1)));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(new byte[12]));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                "x".repeat(16 * 1024 + 1)));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(Double.NaN));
        assertThrows(SecurityException.class, () -> UntrustedJitCodeUnit.copyScalar(
                Double.POSITIVE_INFINITY));
    }

    /**
     * Requires the real Graal native language isolate artifact and cannot
     * run under an ordinary host JVM which lacks that language isolate.
     * This is source-execution evidence, NOT source actor mailbox routing.
     */
    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void actualJitIsolateCompilesAndRunsGuestSourceWithoutExposingValue() {
        try (SharedUntrustedIsolatePool pool =
                 new SharedUntrustedIsolatePool(256L * 1024 * 1024, 2);
             SharedUntrustedIsolatePool.Group group = pool.createGroup();
             UntrustedJitCodeUnit code = new UntrustedJitCodeUnit(
                     group,
                     "pub routine double_value(int n): int { return n * 2; }",
                     "arithmetic")) {
            assertEquals(42, code.invoke(code.generation(), "double_value", 21));
            assertThrows(IllegalStateException.class,
                    () -> code.invoke(UUID.randomUUID(), "double_value", 3));
            assertThrows(SecurityException.class,
                    () -> code.invoke(code.generation(), "double_value", new Object()));
        }
    }
}
