package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopGpuAdmission;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.GpuBufferFence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DopGpuAdmissionTest {
    private static DopKernelIR.Kernel kernel(DataOrientedLayout.Plan plan) {
        return new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE_BIAS, 2.0f, 1.0f);
    }

    private static DopGpuAdmission.DeviceBuffer buffer(
            DataOrientedLayout.Plan plan, String tenant, String device, long generation) {
        return new DopGpuAdmission.DeviceBuffer(plan,
                new GpuBufferFence(new GpuBufferFence.Identity(tenant, device, generation),
                        plan.byteLength()));
    }

    @Test
    void eachDopLayoutProducesExactTypedDeviceBinding() {
        for (var kind : DataOrientedLayout.Kind.values()) {
            int lanes = kind == DataOrientedLayout.Kind.AOSOA ? 4 : 1;
            var plan = new DataOrientedLayout.Plan(kind, 7, 3, 4, 16, lanes);
            var input = buffer(plan, "tenant-a", "gpu-0", 7);
            var output = buffer(plan, "tenant-a", "gpu-0", 8);
            var binding = DopGpuAdmission.preflight(kernel(plan),
                    new DopKernelIR.GpuTarget(64, 8192, 100, true), input, output);
            assertEquals(plan, binding.layout());
            assertEquals(plan.byteLength(), binding.bytesPerBuffer());
            assertEquals(Math.multiplyExact(plan.byteLength(), 2L), binding.totalReservedBytes());
            assertEquals(plan.byteLength(), binding.artifact().requiredBytes());
            assertEquals(7L, binding.artifact().globalRows());
            assertEquals(3, binding.artifact().globalFields());
            assertEquals("tenant-a", binding.input().tenant());
            assertNotEquals(binding.input().allocationGeneration(),
                    binding.output().allocationGeneration());
        }
    }

    @Test
    void equalByteLengthsWithDifferentLogicalLayoutsAreNotInterchangeable() {
        // Two layouts may have identical padded byte sizes but different offsets.
        var aos = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS, 2, 2, 4, 8, 1);
        var soa = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 2, 2, 4, 8, 1);
        assertEquals(aos.byteLength(), soa.byteLength());
        assertNotEquals(aos.byteOffset(0, 1), soa.byteOffset(0, 1));
        assertThrows(IllegalArgumentException.class, () -> DopGpuAdmission.preflight(
                kernel(aos), new DopKernelIR.GpuTarget(64, 8192, 100, true),
                buffer(soa, "a", "gpu", 1), buffer(aos, "a", "gpu", 2)));
    }

    @Test
    void rejectsAliasTenantDeviceGenerationAndBytes() {
        var plan = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 3, 2, 4, 16, 4);
        var target = new DopKernelIR.GpuTarget(64, 8192, 100, true);
        var first = buffer(plan, "a", "gpu", 1);
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first, first));
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first,
                        new DopGpuAdmission.DeviceBuffer(plan, first.fence())));
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first,
                        buffer(plan, "b", "gpu", 2)));
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first,
                        buffer(plan, "a", "other", 2)));
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first,
                        buffer(plan, "a", "gpu", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first,
                        buffer(plan, "a", "gpu", 0)));
        assertThrows(IllegalArgumentException.class, () -> new DopGpuAdmission.DeviceBuffer(
                plan, new GpuBufferFence(new GpuBufferFence.Identity("a", "gpu", 3),
                        plan.byteLength() + 4)));
        var second = buffer(plan, "a", "gpu", 2);
        var ticket = first.fence().beginCommand(first.fence().identity());
        assertThrows(IllegalStateException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first, second));
        first.fence().cancelPending(ticket);
        assertThrows(IllegalStateException.class,
                () -> DopGpuAdmission.preflight(kernel(plan), target, first, second));
        first.fence().acknowledgeCancelledDeviceEvent(ticket, first.fence().identity());
        first.fence().retireAfterBackendRelease(first.fence().identity());
        first.fence().close();
    }

    @Test
    void twoDeviceReservationsMustFitSigned64BitAbi() {
        // This is metadata admission; the test never allocates huge memory.
        long rows = Long.MAX_VALUE / 8L + 1L;
        var plan = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOS,
                rows, 1, 4, 4, 1);
        var input = buffer(plan, "tenant", "gpu", 123);
        var output = buffer(plan, "tenant", "gpu", 124);
        assertThrows(ArithmeticException.class, () -> DopGpuAdmission.preflight(
                kernel(plan), new DopKernelIR.GpuTarget(64, Long.MAX_VALUE,
                        Long.MAX_VALUE, true), input, output));
    }

    @Test
    void emptyDopPlansAreLegalOnHostButNeverDeviceLaunches() {
        var empty = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 0, 1, 4, 8, 1);
        assertEquals(0, empty.byteLength());
        assertThrows(IllegalArgumentException.class,
                () -> DopKernelIR.lowerOpenCl(kernel(empty),
                        new DopKernelIR.GpuTarget(64, 8192, 100, true)));
        assertThrows(IllegalArgumentException.class,
                () -> new DopGpuAdmission.DeviceBuffer(empty,
                        new GpuBufferFence(new GpuBufferFence.Identity("a", "gpu", 1), 4)));
    }
}
