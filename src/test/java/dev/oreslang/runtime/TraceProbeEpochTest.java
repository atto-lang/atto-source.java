package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-level Truffle probe policy only. Passing this class does NOT prove
 * automatic Atto call sites, actor tracing, JIT recompile, or OTLP export.
 */
final class TraceProbeEpochTest {
    @Test
    void runtimeStartsOffAndCannotExceedCompiledCoverage() {
        TraceProbeEpoch probes = new TraceProbeEpoch(TraceProbeEpoch.Level.BOUNDARIES, false);
        assertEquals(TraceProbeEpoch.Level.OFF, probes.currentMode());
        assertEquals(0L, probes.generation());
        assertFalse(probes.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
        assertFalse(probes.shouldRecord(TraceProbeEpoch.Level.CALLS));

        assertTrue(probes.reconfigure(TraceProbeEpoch.Level.BOUNDARIES));
        assertEquals(1L, probes.generation());
        assertTrue(probes.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
        assertFalse(probes.shouldRecord(TraceProbeEpoch.Level.CALLS));

        assertFalse(probes.reconfigure(TraceProbeEpoch.Level.CALLS));
        assertTrue(probes.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
        assertEquals(1L, probes.generation());
        assertTrue(probes.reconfigure(TraceProbeEpoch.Level.OFF));
        assertEquals(2L, probes.generation());
        assertFalse(probes.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
    }

    @Test
    void strippedAndSealedBuildsDoNotAcceptRuntimeReinstrumentation() {
        TraceProbeEpoch stripped = new TraceProbeEpoch(TraceProbeEpoch.Level.OFF, false);
        assertFalse(stripped.reconfigure(TraceProbeEpoch.Level.BOUNDARIES));
        assertFalse(stripped.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));

        TraceProbeEpoch sealed = new TraceProbeEpoch(TraceProbeEpoch.Level.FULL, true);
        assertFalse(sealed.reconfigure(TraceProbeEpoch.Level.CALLS));
        assertEquals(0L, sealed.generation());
        assertFalse(sealed.shouldRecord(TraceProbeEpoch.Level.FULL));
    }

    @Test
    void perDomainPoliciesDoNotLeakToOtherIsolates() {
        TraceProbeEpoch a = new TraceProbeEpoch(TraceProbeEpoch.Level.FULL, false);
        TraceProbeEpoch b = new TraceProbeEpoch(TraceProbeEpoch.Level.FULL, false);
        assertTrue(a.reconfigure(TraceProbeEpoch.Level.CALLS));
        assertTrue(a.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
        assertFalse(b.shouldRecord(TraceProbeEpoch.Level.BOUNDARIES));
        assertEquals(0L, b.generation());
        assertTrue(b.reconfigure(TraceProbeEpoch.Level.FULL));
        assertFalse(a.shouldRecord(TraceProbeEpoch.Level.FULL));
        assertTrue(b.shouldRecord(TraceProbeEpoch.Level.FULL));
    }

    @Test
    void unchangedModeDoesNotCauseExtraInvalidation() {
        TraceProbeEpoch p = new TraceProbeEpoch(TraceProbeEpoch.Level.CALLS, false);
        assertTrue(p.reconfigure(TraceProbeEpoch.Level.OFF));
        assertEquals(0L, p.generation());
        assertTrue(p.reconfigure(TraceProbeEpoch.Level.CALLS));
        assertTrue(p.reconfigure(TraceProbeEpoch.Level.CALLS));
        assertEquals(1L, p.generation());
    }

    @Test
    void nullModesAreRejectedBeforePolicyMutation() {
        TraceProbeEpoch p = new TraceProbeEpoch(TraceProbeEpoch.Level.FULL, false);
        assertThrows(NullPointerException.class, () -> p.reconfigure(null));
        assertThrows(NullPointerException.class, () -> p.shouldRecord(null));
        assertEquals(0L, p.generation());
    }
}
