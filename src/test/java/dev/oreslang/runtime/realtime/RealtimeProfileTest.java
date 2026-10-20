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
                false, false, false, false, false, false, false, false, false);
        assertTrue(RealtimeProfile.admitJavaHardRt(effects, goodBudget()).admitted());
    }

    @Test
    void rejectsManagedHeapOnCertifiedPath() {
        var effects = new RealtimeProfile.Effects(
                true, false, false, false, false, false, false, false, false);
        var result = RealtimeProfile.admitJavaHardRt(effects, goodBudget());
        assertFalse(result.admitted());
        assertEquals("RT004_MANAGED_HEAP_ESCAPE", result.diagnosticCode());
    }

    @Test
    void rejectsCollectorInterference() {
        var effects = new RealtimeProfile.Effects(
                false, true, false, false, false, false, false, false, false);
        var result = RealtimeProfile.admitJavaHardRt(effects, goodBudget());
        assertFalse(result.admitted());
        assertEquals(
                RealtimeProfile.Failure.TRACING_GC_INTERFERENCE,
                result.failure());
        assertEquals("RT011_COLLECTOR_INTERFERENCE", result.diagnosticCode());
    }

    @Test
    void rejectsDynamicCodeAndUnknownFfi() {
        var dynamic = new RealtimeProfile.Effects(
                false, false, true, false, false, false, false, false, false);
        assertEquals(
                RealtimeProfile.Failure.RUNTIME_CODE_GENERATION,
                RealtimeProfile.admitJavaHardRt(dynamic, goodBudget()).failure());

        var ffi = new RealtimeProfile.Effects(
                false, false, false, true, false, false, false, false, false);
        assertEquals(
                RealtimeProfile.Failure.UNKNOWN_FFI,
                RealtimeProfile.admitJavaHardRt(ffi, goodBudget()).failure());
    }

    @Test
    void rejectsOverflowingResponseBound() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
        var overflow = new RealtimeProfile.Budget(
                Long.MAX_VALUE,
                Long.MAX_VALUE - 10,
                8,
                8,
                Long.MAX_VALUE,
                1,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertFalse(RealtimeProfile.admitJavaHardRt(effects, overflow).admitted());
    }

    @Test
    void rejectsImpossibleResponseTimeBound() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
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

    @Test
    void rejectsMissingPeriodBudget() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
        var missing = new RealtimeProfile.Budget(
                1_000_000,
                500_000,
                100_000,
                50_000,
                0,
                64 * 1024,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        var result = RealtimeProfile.admitJavaHardRt(effects, missing);
        assertEquals(RealtimeProfile.Failure.MISSING_TIMING_BUDGET, result.failure());
        assertEquals("RT003_WCET_UNPROVEN", result.diagnosticCode());
    }

    @Test
    void rejectsDeadlineBeyondPeriodForConstrainedDeadlineModel() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
        var invalid = new RealtimeProfile.Budget(
                2_000_000,
                500_000,
                100_000,
                50_000,
                1_000_000,
                64 * 1024,
                RealtimeProfile.Scheduler.EDF);
        assertEquals(
                RealtimeProfile.Failure.MISSING_TIMING_BUDGET,
                RealtimeProfile.admitJavaHardRt(effects, invalid).failure());
    }

    @Test
    void rejectsMissingMemoryBudget() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
        var missing = new RealtimeProfile.Budget(
                1_000_000,
                500_000,
                100_000,
                50_000,
                10_000_000,
                0,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        var result = RealtimeProfile.admitJavaHardRt(effects, missing);
        assertEquals(RealtimeProfile.Failure.MISSING_MEMORY_BUDGET, result.failure());
        assertEquals("RT009_SCHEDULING_UNPROVEN", result.diagnosticCode());
    }

    @Test
    void admitsBoundaryDeadlineAndMinimalExplicitMemoryCap() {
        var effects = new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
        var boundary = new RealtimeProfile.Budget(
                650_000,
                500_000,
                100_000,
                50_000,
                650_000,
                1,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertTrue(RealtimeProfile.admitJavaHardRt(effects, boundary).admitted());
    }
}
