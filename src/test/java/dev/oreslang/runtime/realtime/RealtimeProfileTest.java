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

    private static RealtimeProfile.Proof goodProof() {
        return new RealtimeProfile.Proof(true, true, 32 * 1024);
    }

    private static RealtimeProfile.Effects cleanEffects() {
        return new RealtimeProfile.Effects(
                false, false, false, false, false, false, false, false, false);
    }

    @Test
    void admitsBoundedNoHeapPathWithIndependentProofs() {
        assertTrue(RealtimeProfile.admitJavaHardRt(
                cleanEffects(), goodBudget(), goodProof()).admitted());
    }

    @Test
    void legacyAdmissionWithoutProofFailsClosed() {
        var result = RealtimeProfile.admitJavaHardRt(cleanEffects(), goodBudget());
        assertFalse(result.admitted());
        assertEquals(RealtimeProfile.Failure.MEMORY_BUDGET_UNPROVEN, result.failure());
    }

    @Test
    void rejectsManagedHeapOnCertifiedPath() {
        var effects = new RealtimeProfile.Effects(
                true, false, false, false, false, false, false, false, false);
        var result = RealtimeProfile.admitJavaHardRt(effects, goodBudget(), goodProof());
        assertFalse(result.admitted());
        assertEquals("RT004_MANAGED_HEAP_ESCAPE", result.diagnosticCode());
    }

    @Test
    void rejectsCollectorInterference() {
        var effects = new RealtimeProfile.Effects(
                false, true, false, false, false, false, false, false, false);
        var result = RealtimeProfile.admitJavaHardRt(effects, goodBudget(), goodProof());
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
                RealtimeProfile.admitJavaHardRt(
                        dynamic, goodBudget(), goodProof()).failure());

        var ffi = new RealtimeProfile.Effects(
                false, false, false, true, false, false, false, false, false);
        assertEquals(
                RealtimeProfile.Failure.UNKNOWN_FFI,
                RealtimeProfile.admitJavaHardRt(
                        ffi, goodBudget(), goodProof()).failure());
    }

    @Test
    void rejectsOverflowingResponseBound() {
        var overflow = new RealtimeProfile.Budget(
                Long.MAX_VALUE,
                Long.MAX_VALUE - 10,
                8,
                8,
                0,
                1,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertEquals(
                RealtimeProfile.Failure.MISSING_TIMING_BUDGET,
                RealtimeProfile.admitJavaHardRt(
                        cleanEffects(), overflow, new RealtimeProfile.Proof(true, true, 0)).failure());
    }

    @Test
    void rejectsImpossibleResponseTimeBound() {
        var impossible = new RealtimeProfile.Budget(
                1_000_000,
                950_000,
                100_000,
                0,
                10_000_000,
                64 * 1024,
                RealtimeProfile.Scheduler.EDF);
        assertEquals(
                RealtimeProfile.Failure.MISSING_TIMING_BUDGET,
                RealtimeProfile.admitJavaHardRt(
                        cleanEffects(), impossible, goodProof()).failure());
    }

    @Test
    void allowsAperiodicBudgetWhenSchedulerProofCoversIt() {
        var aperiodic = new RealtimeProfile.Budget(
                1_000_000,
                500_000,
                100_000,
                50_000,
                0,
                64 * 1024,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertTrue(RealtimeProfile.admitJavaHardRt(
                cleanEffects(), aperiodic, goodProof()).admitted());
    }

    @Test
    void doesNotInventConstrainedDeadlineRestriction() {
        var arbitraryDeadline = new RealtimeProfile.Budget(
                2_000_000,
                500_000,
                100_000,
                50_000,
                1_000_000,
                64 * 1024,
                RealtimeProfile.Scheduler.EDF);
        assertTrue(RealtimeProfile.admitJavaHardRt(
                cleanEffects(), arbitraryDeadline, goodProof()).admitted());
    }

    @Test
    void rejectsMissingPeakMemoryProof() {
        var result = RealtimeProfile.admitJavaHardRt(
                cleanEffects(), goodBudget(), new RealtimeProfile.Proof(true, false, 0));
        assertEquals(RealtimeProfile.Failure.MEMORY_BUDGET_UNPROVEN, result.failure());
        assertEquals("RT001_UNBOUNDED_ALLOCATION", result.diagnosticCode());
    }

    @Test
    void rejectsProvenPeakAboveMemoryCap() {
        var result = RealtimeProfile.admitJavaHardRt(
                cleanEffects(),
                goodBudget(),
                new RealtimeProfile.Proof(true, true, 64 * 1024 + 1));
        assertEquals(RealtimeProfile.Failure.MEMORY_BUDGET_UNPROVEN, result.failure());
        assertEquals("RT001_UNBOUNDED_ALLOCATION", result.diagnosticCode());
    }

    @Test
    void permitsZeroByteCapWhenPeakIsProvenZero() {
        var zeroMemory = new RealtimeProfile.Budget(
                1_000_000,
                500_000,
                100_000,
                50_000,
                0,
                0,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertTrue(RealtimeProfile.admitJavaHardRt(
                cleanEffects(),
                zeroMemory,
                new RealtimeProfile.Proof(true, true, 0)).admitted());
    }

    @Test
    void rejectsMissingSchedulerProof() {
        var result = RealtimeProfile.admitJavaHardRt(
                cleanEffects(),
                goodBudget(),
                new RealtimeProfile.Proof(false, true, 32 * 1024));
        assertEquals(RealtimeProfile.Failure.SCHEDULABILITY_UNPROVEN, result.failure());
        assertEquals("RT009_SCHEDULING_UNPROVEN", result.diagnosticCode());
    }

    @Test
    void admitsResponseBoundary() {
        var boundary = new RealtimeProfile.Budget(
                650_000,
                500_000,
                100_000,
                50_000,
                0,
                1,
                RealtimeProfile.Scheduler.FIXED_PRIORITY);
        assertTrue(RealtimeProfile.admitJavaHardRt(
                cleanEffects(),
                boundary,
                new RealtimeProfile.Proof(true, true, 1)).admitted());
    }
}
