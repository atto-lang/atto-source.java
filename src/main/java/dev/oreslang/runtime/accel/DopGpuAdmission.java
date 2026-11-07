package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import java.util.Objects;

/**
 * Typed DOP-to-GPU placement preflight.
 *
 * This binds an OpenCL artifact to an exact AoS/SoA/AoSoA buffer footprint,
 * tenant/device identity and two distinct allocation generations. It performs
 * NO copy, launch, device allocation or actor scheduling. Snapshots are not
 * capabilities: a trusted provider must atomically revalidate at admission,
 * own both buffers and hold their fences through verified device completion.
 */
public final class DopGpuAdmission {
    private DopGpuAdmission() {}

    /** Trusted-provider descriptor: shape identity is NOT inferred from byte count. */
    public record DeviceBuffer(DataOrientedLayout.Plan layout, GpuBufferFence fence) {
        public DeviceBuffer {
            Objects.requireNonNull(layout, "device DOP layout");
            Objects.requireNonNull(fence, "device fence");
            if (layout.rows() == 0 || layout.byteLength() == 0
                    || fence.snapshot().byteLength() != layout.byteLength()) {
                throw new IllegalArgumentException("device allocation and DOP layout disagree");
            }
        }
    }

    public record Binding(
            DataOrientedLayout.Plan layout,
            DopKernelIR.OpenClArtifact artifact,
            GpuBufferFence.Identity input,
            GpuBufferFence.Identity output,
            long bytesPerBuffer,
            long totalReservedBytes) { }

    public static Binding preflight(DopKernelIR.Kernel kernel, DopKernelIR.GpuTarget target,
                                    DeviceBuffer input, DeviceBuffer output) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input == output || input.fence() == output.fence()) {
            throw new IllegalArgumentException("DOP GPU input/output device owners must be distinct");
        }

        // Calls the same typed OpenCL kernel lowering as the GPU backend, not
        // a second independent layout calculation that could drift.
        var artifact = DopKernelIR.lowerOpenCl(kernel, target);
        var shape = kernel.layout();
        long bytes = shape.byteLength();
        if (shape.rows() == 0 || bytes == 0 || bytes != artifact.requiredBytes()) {
            throw new IllegalArgumentException("DOP GPU requires nonempty exact layout");
        }
        if (!shape.equals(input.layout()) || !shape.equals(output.layout())) {
            throw new IllegalArgumentException("DOP GPU kernel and device buffers require identical layouts");
        }
        // Account separately allocated input and output; overflow is rejection.
        long total = Math.multiplyExact(bytes, 2L);

        var src = input.fence().snapshot();
        var dst = output.fence().snapshot();
        if (src.status() != GpuBufferFence.Status.IDLE
                || dst.status() != GpuBufferFence.Status.IDLE) {
            throw new IllegalStateException("DOP GPU device buffer is not idle");
        }
        if (src.byteLength() != bytes || dst.byteLength() != bytes) {
            throw new IllegalArgumentException("DOP GPU buffer bytes must exactly match padded layout");
        }
        var first = src.identity();
        var second = dst.identity();
        if (first.allocationGeneration() <= 0 || second.allocationGeneration() <= 0
                || !first.tenant().equals(second.tenant())
                || !first.device().equals(second.device())
                || first.allocationGeneration() == second.allocationGeneration()) {
            throw new IllegalArgumentException(
                    "DOP GPU requires same tenant/device with distinct nonzero allocation generations");
        }
        return new Binding(shape, artifact, first, second, bytes, total);
    }
    /**
     * Construct the result BEFORE claiming device fences. After claimPair
     * succeeds, assigning the already-created ticket reference cannot allocate
     * or throw, so a Java allocation failure cannot strand two IN_FLIGHT
     * buffers without a returned completion capability.
     */
    public static final class ClaimedBinding {
        private final Binding binding;
        private GpuBufferFence.PairTickets tickets;

        private ClaimedBinding(Binding binding) { this.binding = binding; }
        public Binding binding() { return binding; }
        public GpuBufferFence.PairTickets tickets() {
            return Objects.requireNonNull(tickets, "GPU claim not completed");
        }
    }

    static ClaimedBinding claim(DopKernelIR.Kernel kernel, DopKernelIR.GpuTarget target,
                                DeviceBuffer input, DeviceBuffer output) {
        var plan = preflight(kernel, target, input, output);
        var result = new ClaimedBinding(plan);
        result.tickets = GpuBufferFence.claimPair(input.fence(), output.fence(),
                plan.bytesPerBuffer());
        return result;
    }

}
