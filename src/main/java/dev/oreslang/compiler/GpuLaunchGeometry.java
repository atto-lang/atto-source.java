package dev.oreslang.compiler;

import java.util.Objects;

/**
 * Checked geometry for a future physical GPU provider. This class performs
 * arithmetic and advertised-device-limit checks only: it is NOT proof of
 * device/tenant authority, kernel ABI, buffer ownership or GPU dispatch.
 */
public final class GpuLaunchGeometry {
    private GpuLaunchGeometry() { }

    public record Dim3(long x, long y, long z) {
        public Dim3 {
            if (x < 0 || y < 0 || z < 0)
                throw new IllegalArgumentException(
                        "GPU-LAUNCH-003: dimensions cannot be negative");
        }
    }

    public record Limits(Dim3 maxGrid, Dim3 maxBlock,
                         long maxThreadsPerBlock, long maxSharedBytes) {
        public Limits {
            Objects.requireNonNull(maxGrid, "GPU launch maxGrid");
            Objects.requireNonNull(maxBlock, "GPU launch maxBlock");
            if (maxGrid.x() <= 0 || maxGrid.y() <= 0 || maxGrid.z() <= 0
                    || maxBlock.x() <= 0 || maxBlock.y() <= 0 || maxBlock.z() <= 0
                    || maxThreadsPerBlock <= 0 || maxSharedBytes < 0)
                throw new IllegalArgumentException("GPU-LAUNCH-003: invalid device geometry limits");
        }
    }

    public record Plan(Dim3 grid, Dim3 block, long sharedBytes,
                       long totalGroups, long threadsPerGroup,
                       long totalLogicalThreads, boolean emptyLaunch) { }

    private static long checkedProduct(long left, long right, String kind) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("GPU-LAUNCH-003: " + kind
                    + " product overflows signed 64-bit", overflow);
        }
    }

    /**
     * Caller must supply limits from an authenticated GPU capability;
     * values from untrusted guest source alone are never provider authority.
     */
    public static Plan validate(Dim3 grid, Dim3 block,
                                long sharedBytes, Limits limits) {
        Objects.requireNonNull(grid, "GPU launch grid");
        Objects.requireNonNull(block, "GPU launch block");
        Objects.requireNonNull(limits, "GPU launch limits");

        if (block.x() == 0 || block.y() == 0 || block.z() == 0)
            throw new IllegalArgumentException("GPU-LAUNCH-003: block dimensions must be positive");
        if (grid.x() > limits.maxGrid().x() || grid.y() > limits.maxGrid().y()
                || grid.z() > limits.maxGrid().z()
                || block.x() > limits.maxBlock().x() || block.y() > limits.maxBlock().y()
                || block.z() > limits.maxBlock().z())
            throw new IllegalArgumentException("GPU-LAUNCH-003: launch exceeds device axis limit");
        if (sharedBytes < 0 || sharedBytes > limits.maxSharedBytes())
            throw new IllegalArgumentException("GPU-LAUNCH-003: launch exceeds shared memory budget");

        long threads = checkedProduct(checkedProduct(block.x(), block.y(), "block"),
                block.z(), "block");
        if (threads > limits.maxThreadsPerBlock())
            throw new IllegalArgumentException("GPU-LAUNCH-003: block exceeds threads-per-group budget");

        long groups = checkedProduct(checkedProduct(grid.x(), grid.y(), "grid"),
                grid.z(), "grid");
        long total = checkedProduct(groups, threads, "total launch");
        // A zero grid is admitted but never becomes a zero-sized physical
        // command. The future provider should create an already-complete
        // quiescent completion after normal capability/ownership admission.
        return new Plan(grid, block, sharedBytes, groups, threads, total, groups == 0);
    }
}
