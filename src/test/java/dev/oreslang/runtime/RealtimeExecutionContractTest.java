package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RealtimeExecutionContractTest {
    private static RealtimeExecutionContract.Capabilities qualified() {
        return new RealtimeExecutionContract.Capabilities(
                true, true, true, true, true,
                true, true, true, true, true,
                true, true, true, true, true,
                true, true, true, true, true);
    }

    @Test
    void fullyQualifiedCriticalDomainIsAdmitted() {
        var timing = new RealtimeExecutionContract.TimingBudget(
                1_000_000, 400_000, 100_000, 200_000, 50_000);
        var admission = RealtimeExecutionContract.evaluateHardRealtime(timing, qualified());
        assertTrue(admission.admitted(), admission.failures().toString());
        assertDoesNotThrow(() ->
                RealtimeExecutionContract.requireHardRealtime(timing, qualified()));
    }

    @Test
    void defaultCapabilitiesFailClosed() {
        var timing = new RealtimeExecutionContract.TimingBudget(
                1_000_000, 1, 0, 0, 0);
        var admission = RealtimeExecutionContract.evaluateHardRealtime(
                timing, RealtimeExecutionContract.Capabilities.none());
        assertFalse(admission.admitted());
        assertTrue(admission.failures().size() >= 20);
        assertThrows(IllegalStateException.class, () ->
                RealtimeExecutionContract.requireHardRealtime(
                        timing, RealtimeExecutionContract.Capabilities.none()));
    }

    @Test
    void aotDoesNotImplyHardRealtime() {
        var c = new RealtimeExecutionContract.Capabilities(
                true, true, true,
                false, false, false, false,
                false, false, false, false,
                false, false, false, false,
                false, false, true, false, false);
        var admission = RealtimeExecutionContract.evaluateHardRealtime(
                new RealtimeExecutionContract.TimingBudget(10_000, 100, 0, 0, 0), c);
        assertFalse(admission.admitted());
        assertTrue(admission.failures().stream()
                .anyMatch(s -> s.contains("tracing/managed heap")));
        assertTrue(admission.failures().stream()
                .anyMatch(s -> s.contains("workers")));
        assertTrue(admission.failures().stream()
                .anyMatch(s -> s.contains("timing")));
    }

    @Test
    void dynamicLoadingIsIndependentFromAot() {
        var q = qualified();
        var c = new RealtimeExecutionContract.Capabilities(
                q.fullGuestAot(), q.runtimeCodegenDisabled(), false,
                q.noManagedHeapOnCriticalPath(), q.deterministicActorRegions(),
                q.memoryPreallocated(), q.memoryPrefaulted(), q.criticalWorkersPrestarted(),
                q.boundedDispatch(), q.boundedPriorityInversion(),
                q.criticalWorkerIsolation(), q.osRealtimePolicy(), q.boundedCleanup(),
                q.boundedCallGraph(), q.boundedLoops(), q.boundedQueues(),
                q.blockingIoAbsent(), q.ffiQualified(), q.untrustedJitExcluded(),
                q.timingEvidence());
        var admission = RealtimeExecutionContract.evaluateHardRealtime(
                new RealtimeExecutionContract.TimingBudget(1000, 1, 0, 0, 0), c);
        assertFalse(admission.admitted());
        assertTrue(admission.failures().stream()
                .anyMatch(s -> s.contains("dynamic class/module loading")));
    }

    @Test
    void responseBudgetMustFitDeadline() {
        var timing = new RealtimeExecutionContract.TimingBudget(
                1_000, 700, 200, 150, 1);
        var admission = RealtimeExecutionContract.evaluateHardRealtime(timing, qualified());
        assertFalse(admission.admitted());
        assertEquals(1, admission.failures().size());
        assertTrue(admission.failures().get(0).contains("exceeds deadline"));
    }

    @Test
    void timingOverflowFailsClosed() {
        var timing = new RealtimeExecutionContract.TimingBudget(
                Long.MAX_VALUE, Long.MAX_VALUE, 1, 1, 1);
        assertEquals(Long.MAX_VALUE, timing.worstCaseResponseNanos());
        assertFalse(timing.withinDeadline(),
                "overflowed response-time arithmetic must fail closed");
        assertThrows(IllegalArgumentException.class, () ->
                new RealtimeExecutionContract.TimingBudget(0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new RealtimeExecutionContract.TimingBudget(1, -1, 0, 0, 0));
    }
}
