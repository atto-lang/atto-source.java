package dev.oreslang;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopKernelIR;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DopKernelLoweringTest {
    @Test
    void cpuLoweringAndDeviceSourceAgreeOnTypedLayouts() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            int lanes = kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1;
            var plan = new DataOrientedLayout.Plan(kind, 7, 3, 4, 16, lanes);
            var kernel = new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                    DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
            try (var src = new DopKernelIR.HostBuffer(plan);
                 var dst = new DopKernelIR.HostBuffer(plan)) {
                for (int row = 0; row < 7; row++)
                    for (int field = 0; field < 3; field++)
                        src.write(row, field, (row * 3 + field) * 0.5f);
                DopKernelIR.executeCpu(kernel, src, dst);
                for (int row = 0; row < 7; row++)
                    for (int field = 0; field < 3; field++)
                        assertEquals((row * 3 + field) + 1.0f, dst.read(row, field));
                var artifact = DopKernelIR.lowerOpenCl(kernel,
                        new DopKernelIR.GpuTarget(64, 4096, 100, true));
                assertEquals(plan.byteLength(), artifact.requiredBytes());
                assertEquals(7, artifact.globalRows());
                assertEquals(3, artifact.globalFields());
                assertTrue(artifact.deviceSource().contains("__kernel void oreslang_dop_f32"));
                assertTrue(artifact.deviceSource().contains("get_global_id(1)"));
                assertTrue(artifact.deviceSource().contains("dst[slot]"));
            }
        }
    }

    @Test
    void zeroRowCpuLayoutIsAllowedButGpuDispatchMustFailClosed() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            int lanes = kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1;
            var plan = new DataOrientedLayout.Plan(kind, 0, 2, 4, 16, lanes);
            var kernel = new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                    DopKernelIR.Op.SCALE, 2.0f, 0.0f);
            try (var input = new DopKernelIR.HostBuffer(plan);
                 var output = new DopKernelIR.HostBuffer(plan)) {
                DopKernelIR.executeCpu(kernel, input, output);
                assertEquals(0L, plan.byteLength());
                assertThrows(IllegalArgumentException.class,
                        () -> DopKernelIR.lowerOpenCl(kernel,
                                new DopKernelIR.GpuTarget(64, 4096, 10, true)));
            }
        }
    }

    @Test
    void bufferOwnershipAndDeviceAdmissionFailClosed() {
        var plan = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 2, 1, 4, 8, 1);
        var kernel = new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE, 2.0f, 0.0f);
        var source = new DopKernelIR.HostBuffer(plan);
        try (var loan = source.borrowRead()) {
            assertThrows(IllegalStateException.class, source::take);
            assertThrows(IllegalStateException.class, () -> source.write(0, 0, 1f));
            assertThrows(IllegalStateException.class, source::close);
        }
        var newOwner = source.take();
        assertThrows(IllegalStateException.class, () -> source.read(0, 0));
        assertThrows(IllegalArgumentException.class, () -> DopKernelIR.executeCpu(kernel, newOwner, newOwner));
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.lowerOpenCl(kernel, new DopKernelIR.GpuTarget(64, 1, 10, true)));
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.lowerOpenCl(kernel, new DopKernelIR.GpuTarget(64, 2048, 10, false)));
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.lowerOpenCl(kernel, new DopKernelIR.GpuTarget(32, 2048, 10, true)));
        newOwner.close();
        assertThrows(IllegalStateException.class, () -> newOwner.read(0, 0));
    }

    @Test
    void hostBufferRejectsScalarSlotsThatWouldOverlapFourByteFloatValues() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            int lanes = kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1;
            for (int scalarBytes : new int[] {1, 2, 8}) {
                var plan = new DataOrientedLayout.Plan(
                        kind, 1, 2, scalarBytes, Math.max(4, scalarBytes), lanes);
                assertThrows(IllegalArgumentException.class,
                        () -> new DopKernelIR.HostBuffer(plan));
            }
        }
    }

    @Test
    void layoutAccessFollowsOwnerTransferAndClose() {
        var plan = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS, 2, 1, 4, 4, 1);
        var owner = new DopKernelIR.HostBuffer(plan);
        assertEquals(plan, owner.layout());
        var newOwner = owner.take();
        assertThrows(IllegalStateException.class, owner::layout);
        assertEquals(plan, newOwner.layout());
        newOwner.close();
        assertThrows(IllegalStateException.class, newOwner::layout);
    }

    @Test
    void rejectsUnsupportedScalarWidthAndNonFiniteCoefficients() {
        var p = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS, 2, 3, 8, 16, 1);
        assertThrows(IllegalArgumentException.class, () -> new DopKernelIR.Kernel(
                p, DopKernelIR.Element.F32, DopKernelIR.Op.SCALE, 1, 0));
        var f = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS, 2, 3, 4, 16, 1);
        assertThrows(IllegalArgumentException.class, () -> new DopKernelIR.Kernel(
                f, DopKernelIR.Element.F32, DopKernelIR.Op.SCALE, Float.NaN, 0));
    }
}
