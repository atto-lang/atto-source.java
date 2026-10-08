package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HpcAsyncFramePlanTest {

    private static HpcAsyncFramePlan.Request request(
            HpcAsyncFramePlan.Storage storage,
            HpcAsyncFramePlan.Operation operation,
            boolean interiorPointer, boolean pinnedLease, boolean crossAwaitBorrow,
            boolean compilerProvenBorrow, boolean crossesActor, boolean rawJvmAddress,
            boolean generationFence, int activeTurns, int suspended,
            int waiters, int leases) {
        return new HpcAsyncFramePlan.Request(storage, operation,
                interiorPointer, pinnedLease, crossAwaitBorrow, compilerProvenBorrow,
                crossesActor, rawJvmAddress, generationFence,
                activeTurns, suspended, waiters, leases);
    }

    private static boolean has(HpcAsyncFramePlan.Request request, String phrase) {
        return HpcAsyncFramePlan.blockers(request).stream()
                .anyMatch(reason -> reason.contains(phrase));
    }

    @Test
    void ordinaryManagedContinuationNeverPretendsJvmAddressesArePinned() {
        var p = request(HpcAsyncFramePlan.Storage.GRAAL_MANAGED,
                HpcAsyncFramePlan.Operation.SUSPEND,
                true, false, true, false, false, true,
                true, 0, 1, 1, 0);

        assertTrue(has(p, "raw JVM object addresses"));
        assertTrue(has(p, "self-referential raw pointers"));
        assertTrue(has(p, "no-live-borrow-across-await"));
        assertTrue(has(p, "compiler-proven"));
        assertTrue(has(p, "not implemented"));
    }

    @Test
    void nativeInteriorPointersNeedPinnedLeaseAndCannotMove() {
        var noLease = request(HpcAsyncFramePlan.Storage.NATIVE_ADDRESS_SENSITIVE,
                HpcAsyncFramePlan.Operation.SUSPEND,
                true, false, false, false, false, false,
                true, 1, 0, 0, 0);
        assertTrue(has(noLease, "requires an owning pinned region lease"));

        var pinnedRelocate = request(HpcAsyncFramePlan.Storage.NATIVE_ADDRESS_SENSITIVE,
                HpcAsyncFramePlan.Operation.RELOCATE,
                true, true, true, true, false, false,
                true, 0, 1, 1, 1);
        assertTrue(has(pinnedRelocate, "may not be relocated"));
        assertTrue(has(pinnedRelocate, "cannot invalidate an active interior borrow"));
    }

    @Test
    void offsetBasedFramesCanDescribeRelocationWithoutRawPointers() {
        var safeShape = request(HpcAsyncFramePlan.Storage.NATIVE_RELATIVE_OFFSETS,
                HpcAsyncFramePlan.Operation.RELOCATE,
                false, false, true, true, false, false,
                true, 0, 1, 0, 0);
        var reasons = HpcAsyncFramePlan.blockers(safeShape);
        assertEquals(1, reasons.size(), "only the missing backend should prevent this shape");
        assertTrue(reasons.get(0).contains("not implemented"));
        assertThrows(UnsupportedOperationException.class, () -> reasons.clear());

        var badShape = request(HpcAsyncFramePlan.Storage.NATIVE_RELATIVE_OFFSETS,
                HpcAsyncFramePlan.Operation.RELOCATE,
                true, false, false, false, false, false,
                true, 0, 1, 0, 0);
        assertTrue(has(badShape, "instead of validated offsets"));
    }

    @Test
    void noAsyncBorrowOrFramePointerMayCrossAnActorBoundary() {
        for (var storage : HpcAsyncFramePlan.Storage.values()) {
            var req = request(storage, HpcAsyncFramePlan.Operation.SUSPEND,
                    false, storage == HpcAsyncFramePlan.Storage.NATIVE_ADDRESS_SENSITIVE,
                    false, false, true, false,
                    true, 0, 1, 0, 0);
            assertTrue(has(req, "actor isolation boundaries"));
        }
    }

    @Test
    void groupRetirementNeedsDrainingEveryOutstandingAsyncRoot() {
        for (int i = 0; i < 4; i++) {
            var req = request(HpcAsyncFramePlan.Storage.NATIVE_RELATIVE_OFFSETS,
                    HpcAsyncFramePlan.Operation.RETIRE_GROUP,
                    false, false, false, false, false, false,
                    true, i == 0 ? 1 : 0, i == 1 ? 1 : 0,
                    i == 2 ? 1 : 0, i == 3 ? 1 : 0);
            assertTrue(has(req, "drain turns, suspended frames, waiters"));
        }
    }

    @Test
    void lateWakeAfterPoolRotationMustBeGenerationFenced() {
        var noFence = request(HpcAsyncFramePlan.Storage.NATIVE_RELATIVE_OFFSETS,
                HpcAsyncFramePlan.Operation.RETIRE_GROUP,
                false, false, false, false, false, false,
                false, 0, 0, 0, 0);
        assertTrue(has(noFence, "generation-fenced task/region identifiers"));

        var quiescent = request(HpcAsyncFramePlan.Storage.NATIVE_RELATIVE_OFFSETS,
                HpcAsyncFramePlan.Operation.RETIRE_GROUP,
                false, false, false, false, false, false,
                true, 0, 0, 0, 0);
        assertEquals(1, HpcAsyncFramePlan.blockers(quiescent).size(),
                "the runtime admission implementation must still be missing");
    }

    @Test
    void rejectNegativeCountsAndMissingOperands() {
        assertThrows(NullPointerException.class, () -> HpcAsyncFramePlan.blockers(null));
        assertThrows(NullPointerException.class, () ->
                request(null, HpcAsyncFramePlan.Operation.SUSPEND,
                        false, false, false, false, false, false,
                        true, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                request(HpcAsyncFramePlan.Storage.GRAAL_MANAGED,
                        HpcAsyncFramePlan.Operation.RETIRE_GROUP,
                        false, false, false, false, false, false,
                        true, 0, 0, -1, 0));
    }
}
