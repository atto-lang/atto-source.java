package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class HpcActorGroupGcPlanTest {

    private static HpcActorGroupGcPlan.Request request(
            boolean trusted, HpcActorGroupGcPlan.HeapBackend backend,
            HpcActorGroupGcPlan.Policy policy,
            int groups, int active, int pending, int leases, long bytes) {
        return new HpcActorGroupGcPlan.Request(trusted, backend, policy,
                groups, active, pending, leases, bytes, 80, 100);
    }

    @Test
    void aLogicalActorHeapCannotClaimIndependentCollection() {
        for (HpcActorGroupGcPlan.Policy policy : new HpcActorGroupGcPlan.Policy[]{
                HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                HpcActorGroupGcPlan.Policy.ROTATE_GROUPS,
                HpcActorGroupGcPlan.Policy.DETERMINISTIC_NO_GC}) {
            var result = HpcActorGroupGcPlan.evaluate(request(true,
                    HpcActorGroupGcPlan.HeapBackend.SHARED_JVM_HEAP,
                    policy, 2, 0, 0, 0, 0));
            assertEquals(HpcActorGroupGcPlan.Decision.REJECT, result.decision());
            assertFalse(result.independentGcHeap());
        }
    }

    @Test
    void defaultGcNeverPromisesSuppressionOrIndependentCollection() {
        var result = HpcActorGroupGcPlan.evaluate(request(false,
                HpcActorGroupGcPlan.HeapBackend.SHARED_JVM_HEAP,
                HpcActorGroupGcPlan.Policy.VM_DEFAULT,
                1, 5, 2, 3, 70));
        assertEquals(HpcActorGroupGcPlan.Decision.VM_OWNS_GC, result.decision());
        assertFalse(result.independentGcHeap());
        assertTrue(result.reasons().get(0).contains("automatic VM garbage collection"));
    }

    @Test
    void trustedGraalIsolatesAndWorkersCanOnlyPlanMaintenanceNeverRunItYet() {
        for (var backend : new HpcActorGroupGcPlan.HeapBackend[]{
                HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                HpcActorGroupGcPlan.HeapBackend.DEDICATED_WORKER_PROCESS}) {
            var result = HpcActorGroupGcPlan.evaluate(request(true, backend,
                    HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                    1, 0, 0, 0, 50));
            assertTrue(result.independentGcHeap());
            assertEquals(HpcActorGroupGcPlan.Decision.BACKEND_NOT_IMPLEMENTED,
                    result.decision());
            assertTrue(result.reasons().stream().anyMatch(r -> r.contains("automatic collections")));
            assertThrows(UnsupportedOperationException.class,
                    () -> result.reasons().add("fake approval"));
        }
    }

    @Test
    void hotActivityPendingCompletionsAndDmaLeasesAllBlockCollection() {
        for (int i = 0; i < 3; i++) {
            int active = i == 0 ? 1 : 0;
            int pending = i == 1 ? 1 : 0;
            int leases = i == 2 ? 1 : 0;
            var result = HpcActorGroupGcPlan.evaluate(request(true,
                    HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                    HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                    1, active, pending, leases, 20));
            assertEquals(HpcActorGroupGcPlan.Decision.DRAIN_FIRST, result.decision());
        }
    }

    @Test
    void budgetPressureRejectsWorkInsteadOfPretendingToPostponeAutomaticGc() {
        var soft = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.DEDICATED_WORKER_PROCESS,
                HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                1, 1, 0, 0, 80));
        assertEquals(HpcActorGroupGcPlan.Decision.DRAIN_FIRST, soft.decision());
        assertTrue(soft.reasons().stream().anyMatch(r -> r.contains("soft heap budget")));

        var hard = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.DEDICATED_WORKER_PROCESS,
                HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                1, 1, 1, 1, 100));
        assertEquals(HpcActorGroupGcPlan.Decision.REJECT_NEW_WORK, hard.decision());
    }

    @Test
    void rotationNeedsTwoIndependentPoolsAndFullQuiescence() {
        var one = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                HpcActorGroupGcPlan.Policy.ROTATE_GROUPS, 1, 0, 0, 0, 5));
        assertEquals(HpcActorGroupGcPlan.Decision.REJECT, one.decision());

        var draining = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                HpcActorGroupGcPlan.Policy.ROTATE_GROUPS, 2, 0, 1, 0, 5));
        assertEquals(HpcActorGroupGcPlan.Decision.DRAIN_FIRST, draining.decision());

        var idle = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                HpcActorGroupGcPlan.Policy.ROTATE_GROUPS, 2, 0, 0, 0, 5));
        assertEquals(HpcActorGroupGcPlan.Decision.BACKEND_NOT_IMPLEMENTED,
                idle.decision());
    }

    @Test
    void untrustedCannotControlGcEvenWithIndependentHeap() {
        for (var backend : HpcActorGroupGcPlan.HeapBackend.values()) {
            for (var policy : new HpcActorGroupGcPlan.Policy[]{
                    HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                    HpcActorGroupGcPlan.Policy.ROTATE_GROUPS,
                    HpcActorGroupGcPlan.Policy.DETERMINISTIC_NO_GC}) {
                var result = HpcActorGroupGcPlan.evaluate(
                        request(false, backend, policy, 2, 0, 0, 0, 0));
                assertEquals(HpcActorGroupGcPlan.Decision.REJECT, result.decision());
            }
        }
    }

    @Test
    void nativeRegionsDoNotNeedTracingGcButBackendStillMustBeProven() {
        var result = HpcActorGroupGcPlan.evaluate(request(true,
                HpcActorGroupGcPlan.HeapBackend.NATIVE_REGIONS,
                HpcActorGroupGcPlan.Policy.DETERMINISTIC_NO_GC,
                1, 0, 0, 0, 0));
        assertEquals(HpcActorGroupGcPlan.Decision.BACKEND_NOT_IMPLEMENTED,
                result.decision());
        assertFalse(result.independentGcHeap());
    }

    @Test
    void rejectInvalidBudgetsCountsAndMissingInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                request(true, HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                        HpcActorGroupGcPlan.Policy.ROTATE_GROUPS, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                request(true, HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                        HpcActorGroupGcPlan.Policy.ROTATE_GROUPS, 2, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new HpcActorGroupGcPlan.Request(true,
                        HpcActorGroupGcPlan.HeapBackend.GRAAL_POLYGLOT_ISOLATE,
                        HpcActorGroupGcPlan.Policy.MAINTENANCE_WINDOW,
                        2, 0, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(NullPointerException.class, () ->
                HpcActorGroupGcPlan.evaluate(null));
        assertThrows(NullPointerException.class, () ->
                new HpcActorGroupGcPlan.Request(true, null,
                        HpcActorGroupGcPlan.Policy.VM_DEFAULT,
                        1, 0, 0, 0, 0, 1, 2));
    }
}
