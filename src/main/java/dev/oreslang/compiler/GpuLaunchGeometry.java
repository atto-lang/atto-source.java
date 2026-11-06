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
                       long totalDispatchedLanes, boolean emptyLaunch) { }

    /** Logical work extent must remain distinct from rounded-up physical lanes. */
    public record LogicalPlan(Dim3 over, Dim3 group, long logicalWorkItems,
                              Plan physical) {
        public LogicalPlan {
            Objects.requireNonNull(over, "logical extent");
            Objects.requireNonNull(group, "logical group");
            Objects.requireNonNull(physical, "physical plan");
        }
    }

    private static long checkedCeilDiv(long extent, long group) {
        if (group <= 0) throw new IllegalArgumentException(
                "GPU-LAUNCH-003: group dimensions must be positive");
        // Avoid extent + group - 1, which can overflow before division.
        return extent / group + (extent % group == 0 ? 0 : 1);
    }

    /**
     * Short-form `over/group`: compute a device-constrained physical grid
     * while preserving the original logical extent for later padded-tail,
     * group-barrier convergence and per-index buffer-footprint proof.
     */
    public static LogicalPlan validateLogical(Dim3 over, Dim3 group,
                                              long sharedBytes, Limits limits) {
        Objects.requireNonNull(over, "logical GPU work extent");
        Objects.requireNonNull(group, "logical GPU workgroup");
        Objects.requireNonNull(limits, "device limits");
        Dim3 grid = new Dim3(checkedCeilDiv(over.x(), group.x()),
                checkedCeilDiv(over.y(), group.y()),
                checkedCeilDiv(over.z(), group.z()));
        // A zero logical domain is an admitted no-op, never a physical zero-grid dispatch.
        Plan physical = validateInternal(grid, group, sharedBytes, limits, true);
        long logicalCount = (over.x() == 0 || over.y() == 0 || over.z() == 0)
                ? 0 : checkedProduct(checkedProduct(over.x(), over.y(), "logical"),
                        over.z(), "logical");
        return new LogicalPlan(over, group, logicalCount, physical);
    }

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
        return validateInternal(grid, block, sharedBytes, limits, false);
    }

    // Empty grids are only representable as private quiescent plans derived
    // from a zero logical 'over' extent. Exact 'grid/block' forbids them.
    private static Plan validateInternal(Dim3 grid, Dim3 block,
                                         long sharedBytes, Limits limits,
                                         boolean allowEmptyLogicalGrid) {
        Objects.requireNonNull(grid, "GPU launch grid");
        Objects.requireNonNull(block, "GPU launch block");
        Objects.requireNonNull(limits, "GPU launch limits");

        if (block.x() == 0 || block.y() == 0 || block.z() == 0)
            throw new IllegalArgumentException("GPU-LAUNCH-003: block dimensions must be positive");
        if (!allowEmptyLogicalGrid && (grid.x() == 0 || grid.y() == 0 || grid.z() == 0))
            throw new IllegalArgumentException("GPU-LAUNCH-003: physical grid dimensions must be positive");
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

        // Zero in ANY grid axis means zero groups, even when the other two
        // extents would overflow if multiplied before the zero is observed.
        boolean empty = grid.x() == 0 || grid.y() == 0 || grid.z() == 0;
        long groups = empty ? 0
                : checkedProduct(checkedProduct(grid.x(), grid.y(), "grid"),
                        grid.z(), "grid");
        long total = checkedProduct(groups, threads, "total launch");
        // Only a zero logical extent may create this empty, non-dispatching
        // quiescent plan; exact physical launches reject zero grid axes.
        return new Plan(grid, block, sharedBytes, groups, threads, total, groups == 0);
    }
}
