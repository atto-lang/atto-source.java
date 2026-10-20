package dev.oreslang.runtime.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RealtimeProfileTest {
    private static RealtimeProfile.Budget goodBudget() {
        return new RealtimeProfile.Budget(
                1_000_000,
                500_000,
                100_000,
                50_000,
                10_000_000,
                64 * 1024,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
    }

    @Test
    void admitsBoundedNoHeapPath() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false);
        assertTrue(RealtimeProfile.admitJavaHardRt(effects, goodBudget()).admitted());
    }

    @Test
    void rejectsManagedHeapOnCertifiedPath() {
        var effects = new RealtimeProfile.Effects(
                true, false, false, false, false, false, false, false);
        var result = RealtimeProfile.admitJavaHardRt(effects, goodBudget());
        assertFalse(result.admitted());
        assertEquals("RT004_MANAGED_HEAP_ESCAPE", result.diagnosticCode());
    }

    @Test
    void rejectsDynamicCodeAndUnknownFfi() {
        var dynamic = new RealtimeProfile.Effects(
                false, true, false, false, false, false, false, false);
        assertEquals(
                RealtimeProfile.Failure.RUNTIME_CODE_GENERATION,
                RealtimeProfile.admitJavaHardRt(dynamic, goodBudget()).failure());

        var ffi = new RealtimeProfile.Effects(
                false, false, true, false, false, false, false, false);
        assertEquals(
                RealtimeProfile.Failure.UNKNOWN_FFI,
                RealtimeProfile.admitJavaHardRt(ffi, goodBudget()).failure());
    }

    @Test
    void rejectsImpossibleResponseTimeBound() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false);
        var impossible = new RealtimeProfile.Budget(
                1_000_000,
                950_000,
                100_000,
                0,
                10_000_000,
                64 * 1024,
                RealtimeProfile.Scheduler.EDF);
        assertFalse(RealtimeProfile.admitJavaHardRt(effects, impossible).admitted());
    }
}
