package dev.oreslang;

import dev.oreslang.compiler.GpuLaunchGeometry;
import dev.oreslang.compiler.GpuLaunchGeometry.Dim3;
import dev.oreslang.compiler.GpuLaunchGeometry.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GpuLaunchGeometryTest {
    private static final Limits LIMITS = new Limits(
            new Dim3(65535, 65535, 65535),
            new Dim3(1024, 1024, 64),
            1024, 49152);

    @Test
    void threeDimensionalShapeHasCheckedGroupAndLaneCounts() {
        var plan = GpuLaunchGeometry.validate(
                new Dim3(10, 3, 2), new Dim3(8, 4, 2), 4096, LIMITS);
        assertEquals(60, plan.totalGroups());
        assertEquals(64, plan.threadsPerGroup());
        assertEquals(3840, plan.totalDispatchedLanes());
        assertEquals(4096, plan.sharedBytes());
        assertFalse(plan.emptyLaunch());
    }

    @Test
    void zeroPhysicalGridRejectsBeforeSubmission() {
        for (Dim3 grid : new Dim3[] {
                new Dim3(0, 3, 2), new Dim3(3, 0, 2), new Dim3(3, 2, 0)
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    GpuLaunchGeometry.validate(grid, new Dim3(8, 4, 2), 0, LIMITS));
        }
    }

    @Test
    void zeroInLastGridAxisSkipsBeforeOtherwiseOverflowingProduct() {
        Limits limits = new Limits(
                new Dim3(Long.MAX_VALUE, Long.MAX_VALUE, 1),
                new Dim3(64, 1, 1), 64, 0);
        var plan = GpuLaunchGeometry.validateLogical(
                new Dim3(Long.MAX_VALUE, Long.MAX_VALUE, 0),
                new Dim3(64, 1, 1), 0, limits);
        assertEquals(0, plan.physical().totalGroups());
        assertTrue(plan.physical().emptyLaunch());
    }

    @Test
    void logicalExtentProducesCheckedPaddedPhysicalGrid() {
        var p = GpuLaunchGeometry.validateLogical(
                new Dim3(1000, 1, 1), new Dim3(256, 1, 1), 0, LIMITS);
        assertEquals(1000, p.logicalWorkItems());
        assertEquals(4, p.physical().grid().x());
        assertEquals(1024, p.physical().totalDispatchedLanes());
        assertEquals(256, p.physical().threadsPerGroup());
    }

    @Test
    void logical2dPadsAxesWithoutChangingWorkItemCount() {
        var p = GpuLaunchGeometry.validateLogical(
                new Dim3(35, 17, 1), new Dim3(16, 16, 1), 0, LIMITS);
        assertEquals(595, p.logicalWorkItems());
        assertEquals(3, p.physical().grid().x());
        assertEquals(2, p.physical().grid().y());
        assertEquals(1536, p.physical().totalDispatchedLanes());
    }

    @Test
    void logicalZeroExtentHasQuiescentCompletionWithoutMultiplicationOverflow() {
        var p = GpuLaunchGeometry.validateLogical(
                new Dim3(0, 65535, 65535),
                new Dim3(1, 1, 1), 0, LIMITS);
        assertEquals(0, p.logicalWorkItems());
        assertEquals(0, p.physical().totalDispatchedLanes());
        assertTrue(p.physical().emptyLaunch());
    }

    @Test
    void emptyLogicalLaunchStillChecksNonzeroAxisAndSharedMemoryCaps() {
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchGeometry.validateLogical(
                        new Dim3(0, 65536, 1), new Dim3(1, 1, 1), 0, LIMITS));
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchGeometry.validateLogical(
                        new Dim3(0, 1, 1), new Dim3(1, 1, 1), 49153, LIMITS));
    }

    @Test
    void logicalZeroAndNegativeGroupAreDenied() {
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchGeometry.validateLogical(
                        new Dim3(8, 1, 1), new Dim3(0, 1, 1), 0, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new Dim3(4, -1, 1));
    }

    @Test
    void hugeLogicalExtentCannotWrapDuringCeilDivOrPhysicalSizing() {
        Limits huge = new Limits(
                new Dim3(Long.MAX_VALUE, 1, 1),
                new Dim3(2, 1, 1), 2, 0);
        assertThrows(IllegalArgumentException.class, () ->
                GpuLaunchGeometry.validateLogical(
                        new Dim3(Long.MAX_VALUE, 1, 1),
                        new Dim3(2, 1, 1), 0, huge));
    }

    @Test
    void zeroAndNegativeBlockComponentsReject() {
        for (int axis = 0; axis < 3; axis++) {
            long[] zeros = {4, 2, 1};
            zeros[axis] = 0;
            var block = new Dim3(zeros[0], zeros[1], zeros[2]);
            assertThrows(IllegalArgumentException.class,
                    () -> GpuLaunchGeometry.validate(new Dim3(1, 1, 1), block, 0, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> new Dim3(-1, 1, 1));
    }

    @Test
    void perAxisAndTotalThreadsCapsAreIndependent() {
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(1, 1, 1),
                        new Dim3(1025, 1, 1), 0, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(1, 1, 1),
                        new Dim3(64, 32, 1), 0, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(65536, 1, 1),
                        new Dim3(1, 1, 1), 0, LIMITS));
    }

    @Test
    void rejectsSharedMemoryUnderflowAndOverBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(1, 1, 1),
                        new Dim3(32, 1, 1), -1, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(1, 1, 1),
                        new Dim3(32, 1, 1), 49153, LIMITS));
    }

    @Test
    void checked64BitGroupAndGlobalProductsCannotWrap() {
        Limits huge = new Limits(
                new Dim3(Long.MAX_VALUE, Long.MAX_VALUE, 3),
                new Dim3(2, 2, 2), 8, 0);
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(Long.MAX_VALUE, 2, 1),
                        new Dim3(1, 1, 1), 0, huge));
        assertThrows(IllegalArgumentException.class,
                () -> GpuLaunchGeometry.validate(new Dim3(Long.MAX_VALUE, 1, 1),
                        new Dim3(2, 1, 1), 0, huge));
    }

    @Test
    void rejectsMalformedDeviceCapsBeforeAnyLaunch() {
        assertThrows(IllegalArgumentException.class,
                () -> new Limits(new Dim3(0, 1, 1), new Dim3(2, 1, 1), 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Limits(new Dim3(1, 1, 1), new Dim3(2, 1, 1), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Limits(new Dim3(1, 1, 1), new Dim3(2, 1, 1), 2, -1));
    }
}
