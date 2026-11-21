package dev.oreslang.runtime;

import dev.oreslang.runtime.accel.DopKernelIR;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary and fail-closed checks for the CPU-only region contract. */
final class DopCpuBoundaryContractTest {
    private static DataOrientedLayout.Plan plan(DataOrientedLayout.Kind kind, long rows) {
        return new DataOrientedLayout.Plan(kind, rows, 3, Float.BYTES, 16,
                kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1);
    }

    @Test
    void zeroRowBuffersAdmitOnlyAnEmptyRegionAcrossAllLayouts() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var shape = plan(kind, 0);
            assertEquals(0L, shape.byteLength());
            var input = new DopKernelIR.HostBuffer(shape);
            var output = new DopKernelIR.HostBuffer(shape);
            var kernel = new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                    DopKernelIR.Op.SCALE_BIAS, 2f, 1f);
            var src = DopRegionSchedule.Access.rows(input.regionRoot(), 2, 0, 0,
                    DopRegionSchedule.Mode.READ);
            var dst = DopRegionSchedule.Access.rows(output.regionRoot(), 2, 0, 0,
                    DopRegionSchedule.Mode.WRITE);
            assertTrue(src.root().isLiveOwned());
            assertDoesNotThrow(() -> DopKernelIR.executeCpuRegion(kernel, input, output, src, dst));
            assertDoesNotThrow(() -> DopKernelIR.executeCpu(kernel, input, output));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(0, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> DopRegionSchedule.Access.rows(input.regionRoot(), 0, 0, 1,
                            DopRegionSchedule.Mode.READ));
            input.close();
            output.close();
            assertFalse(src.root().isLiveOwned());
            assertFalse(dst.root().isLiveOwned());
        }
    }

    @Test
    void allCpuScalarOperatorsHonorFieldAndRowFootprints() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            var shape = plan(kind, 7);
            for (var op : DopKernelIR.Op.values()) {
                var source = new DopKernelIR.HostBuffer(shape);
                var destination = new DopKernelIR.HostBuffer(shape);
                for (int row = 0; row < 7; row++) {
                    for (int field = 0; field < 3; field++) {
                        source.write(row, field, (float) row + field + .5f);
                        destination.write(row, field, -99f);
                    }
                }
                var kernel = new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                        op, 2f, 3f);
                var src = DopRegionSchedule.Access.rows(source.regionRoot(), 2, 1, 5,
                        DopRegionSchedule.Mode.READ);
                var dst = DopRegionSchedule.Access.rows(destination.regionRoot(), 2, 1, 5,
                        DopRegionSchedule.Mode.WRITE);
                DopKernelIR.executeCpuRegion(kernel, source, destination, src, dst);
                for (int row = 0; row < 7; row++) {
                    for (int field = 0; field < 3; field++) {
                        float v = row + field + .5f;
                        float expected = (field == 2 && row >= 1 && row < 6)
                                ? switch (op) {
                                    case SCALE -> v * 2f;
                                    case BIAS -> v + 3f;
                                    case SCALE_BIAS -> v * 2f + 3f;
                                  }
                                : -99f;
                        assertEquals(expected, destination.read(row, field));
                    }
                }
                source.close();
                destination.close();
            }
        }
    }

    @Test
    void invalidEffectsAndShapeMismatchNeverMutateOutput() {
        var shape = plan(DataOrientedLayout.Kind.SOA, 7);
        var otherShape = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS,
                7, 3, Float.BYTES, 16, 1);
        var source = new DopKernelIR.HostBuffer(shape);
        var destination = new DopKernelIR.HostBuffer(shape);
        source.write(0, 0, 20f);
        destination.write(0, 0, 77f);
        var validSrc = DopRegionSchedule.Access.rows(source.regionRoot(), 0, 0, 1,
                DopRegionSchedule.Mode.READ);
        var validDst = DopRegionSchedule.Access.rows(destination.regionRoot(), 0, 0, 1,
                DopRegionSchedule.Mode.WRITE);
        var kernel = new DopKernelIR.Kernel(shape, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE, 2f, 0f);
        var wrongEffect = DopRegionSchedule.Access.rows(destination.regionRoot(), 0, 0, 1,
                DopRegionSchedule.Mode.ATOMIC);
        var mismatch = new DopKernelIR.Kernel(otherShape, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE, 2f, 0f);
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.executeCpuRegion(kernel, source, destination, validSrc, wrongEffect));
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.executeCpuRegion(mismatch, source, destination, validSrc, validDst));
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.executeCpuRegion(kernel, source, source, validSrc,
                        DopRegionSchedule.Access.rows(source.regionRoot(), 0, 0, 1,
                                DopRegionSchedule.Mode.WRITE)));
        assertEquals(77f, destination.read(0, 0));
        assertEquals(20f, source.read(0, 0));
        assertDoesNotThrow(() -> DopRegionSchedule.requireLiveOwned(
                new DopRegionSchedule.Task("valid", List.of(validSrc, validDst))));
        source.close();
        destination.close();
    }

    @Test
    void overflowingAndUnsupportedPhysicalShapesFailBeforeAllocation() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            assertThrows(ArithmeticException.class,
                    () -> plan(kind, Long.MAX_VALUE));
        }
        var tooLarge = plan(DataOrientedLayout.Kind.AOS, Integer.MAX_VALUE);
        assertTrue(tooLarge.byteLength() > Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> new DopKernelIR.HostBuffer(tooLarge));
        for (int scalarWidth : new int[] {1, 2, 8}) {
            var shape = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA,
                    3, 1, scalarWidth, 16, 1);
            assertThrows(IllegalArgumentException.class, () -> new DopKernelIR.HostBuffer(shape));
        }
    }
}
