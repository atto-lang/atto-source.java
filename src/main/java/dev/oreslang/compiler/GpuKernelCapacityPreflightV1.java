package dev.oreslang.compiler;

import java.util.List;
import java.util.Objects;

import dev.oreslang.compiler.GpuLaunchFootprints.Access;
import dev.oreslang.compiler.GpuLaunchFootprints.Limits;
import dev.oreslang.compiler.GpuLaunchFootprints.QueueScope;
import dev.oreslang.compiler.GpuLaunchFootprints.View;

/**
 * An independent host-side capacity predicate for canonical 1D GPU kernels.
 *
 * Inputs MUST come from a trusted immutable provider snapshot once a provider
 * exists; caller-created View records by themselves are NOT authenticated
 * allocations or proof of residency. GpuLaunchFootprints validates overlapping
 * writable loans and tenant/device/queue shape. This pass additionally checks
 * f32 element counts against both source bound n and logical over: [n].
 *
 * No output of this class may authorize dispatch, pin actual device buffers,
 * grant a loan, or report completed GPU work. A verified provider-issued
 * generation-scoped fence and physical backend are still mandatory.
 */
public final class GpuKernelCapacityPreflightV1 {
    private GpuKernelCapacityPreflightV1() { }

    public enum Evidence { SNAPSHOT_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE }

    public record Argument(String parameter, Access expectedAccess, View view) {
        public Argument {
            if (parameter == null || parameter.isBlank() || parameter.length() > 256)
                throw invalid("missing bounded GPU parameter name");
            Objects.requireNonNull(expectedAccess, "GPU expected access");
            Objects.requireNonNull(view, "GPU device buffer view");
        }
    }

    public record Report(long logicalItems, long requiredBytesPerView,
                         int checkedViews, Evidence evidence) {
        public Report {
            if (logicalItems < 0 || requiredBytesPerView < 0 || checkedViews < 0
                    || evidence != Evidence.SNAPSHOT_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE)
                throw invalid("invalid non-executable GPU capacity report");
        }
        public boolean executable() { return false; }
        public boolean physicalDispatchRequired() { return logicalItems > 0; }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("GPU-LAUNCH-005: " + message);
    }

    private static void checkAccess(Access expected, Access actual) {
        if (expected == Access.READ && actual == Access.WRITE)
            throw invalid("write-only device buffer cannot satisfy a read parameter");
        if (expected == Access.WRITE && actual == Access.READ)
            throw invalid("read-only device buffer cannot satisfy a write parameter");
        if (expected == Access.READ_WRITE && actual != Access.READ_WRITE)
            throw invalid("read/write kernel parameter requires read/write device view");
    }

    /**
     * Check the 1D scalar n against exact logical over and every captured
     * device slice. Even on an empty logical workload all supplied views
     * receive normal tenant, generation, overlap and quota preflight.
     *
     * The source tail-guard pass must separately prove that every indexed
     * load/store is the same lane-zero id guarded by i < n. Here the caller
     * supplies only scalar values and source-shaped views, not that proof.
     */
    public static Report validateF32(QueueScope scope, long logicalOver,
                                     long kernelN, List<Argument> arguments,
                                     Limits limits) {
        Objects.requireNonNull(arguments, "GPU capacity arguments");
        if (logicalOver < 0 || kernelN < 0)
            throw invalid("logical extent and kernel n must be nonnegative");
        if (logicalOver != kernelN)
            throw invalid("logical over extent and kernel n disagree");
        long required;
        try {
            required = Math.multiplyExact(kernelN, Float.BYTES);
        } catch (ArithmeticException overflow) {
            throw invalid("GPU element count overflows required device bytes");
        }
        if (arguments.isEmpty())
            throw invalid("GPU f32 kernel needs explicit view arguments");
        if (arguments.size() > 64)
            throw invalid("GPU kernel buffer argument count exceeds source ABI quota");
        List<View> views = arguments.stream().map(Argument::view).toList();
        GpuLaunchFootprints.validate(scope, views, limits);
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (Argument argument : arguments) {
            if (!names.add(argument.parameter()))
                throw invalid("duplicate GPU kernel buffer parameter binding");
            checkAccess(argument.expectedAccess(), argument.view().access());
            if (argument.view().elementAlignment() < Float.BYTES
                    || argument.view().byteOffset() % Float.BYTES != 0
                    || argument.view().byteLength() % Float.BYTES != 0)
                throw invalid("f32 view requires four-byte alignment");
            if (argument.view().byteLength() < required)
                throw invalid("GPU kernel n exceeds bounded device view element capacity");
        }
        return new Report(kernelN, required, arguments.size(),
                Evidence.SNAPSHOT_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE);
    }
}
