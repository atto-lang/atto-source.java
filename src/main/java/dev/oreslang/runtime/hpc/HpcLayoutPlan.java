package dev.oreslang.runtime.hpc;

/**
 * Inert cache-line/region layout calculator for the future trusted-HPC backend.
 *
 * <p>This class DOES NOT allocate memory, pin pages, verify physical cache-line
 * size or NUMA placement, or expose a guest capability. A native allocator must
 * verify that its base address is aligned and that the requested NUMA node is
 * actually present and assigned before interpreting these offsets.</p>
 */
public final class HpcLayoutPlan {

    private static final int MAX_RING_SLOTS = 1 << 24;

    private HpcLayoutPlan() {
    }

    public static RingLayout ring(int capacity, int payloadBytes, int cacheLineBytes) {
        if (!isPowerOfTwo(capacity) || capacity < 2 || capacity > MAX_RING_SLOTS) {
            throw new IllegalArgumentException("ring capacity must be power-of-two between 2 and "
                    + MAX_RING_SLOTS);
        }
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("ring payload width must be positive");
        }
        checkAlignment(cacheLineBytes);

        // MPMC-capable planning only: an eight-byte per-slot sequence prefix.
        // SPSC/MPSC algorithms may need a different layout and synchronization.
        long stride = alignUp(Math.addExact((long) payloadBytes, Long.BYTES), cacheLineBytes);
        long start = Math.multiplyExact(2L, (long) cacheLineBytes);
        long total = Math.addExact(start, Math.multiplyExact(stride, (long) capacity));
        return new RingLayout(capacity, payloadBytes, cacheLineBytes, stride, start, total);
    }

    public static ArenaLayout arena(long requestedBytes, int alignmentBytes, int numaNode) {
        if (requestedBytes <= 0) {
            throw new IllegalArgumentException("arena size must be positive");
        }
        if (numaNode < 0) {
            throw new IllegalArgumentException("NUMA node must be nonnegative");
        }
        checkAlignment(alignmentBytes);
        long reservedBytes = alignUp(requestedBytes, alignmentBytes);
        return new ArenaLayout(requestedBytes, alignmentBytes, numaNode, reservedBytes);
    }

    private static void checkAlignment(int alignment) {
        if (!isPowerOfTwo(alignment) || alignment < 32 || alignment > 4096) {
            throw new IllegalArgumentException(
                    "alignment must be power-of-two between 32 and 4096 bytes");
        }
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    private static long alignUp(long size, int alignment) {
        long rounded = Math.addExact(size, (long) alignment - 1);
        return Math.multiplyExact(rounded / alignment, alignment);
    }

    /** Immutable plan: actual slot base must itself be cache-line aligned. */
    public static final class RingLayout {
        private final int capacity;
        private final int payloadBytes;
        private final int cacheLineBytes;
        private final long slotStrideBytes;
        private final long slotsOffsetBytes;
        private final long totalBytes;

        private RingLayout(int capacity, int payloadBytes, int cacheLineBytes,
                           long slotStrideBytes, long slotsOffsetBytes, long totalBytes) {
            this.capacity = capacity;
            this.payloadBytes = payloadBytes;
            this.cacheLineBytes = cacheLineBytes;
            this.slotStrideBytes = slotStrideBytes;
            this.slotsOffsetBytes = slotsOffsetBytes;
            this.totalBytes = totalBytes;
        }

        public int capacity() {
            return capacity;
        }

        public int payloadBytes() {
            return payloadBytes;
        }

        public int requiredBaseAlignmentBytes() {
            return cacheLineBytes;
        }

        public long headOffsetBytes() {
            return 0L;
        }

        public long tailOffsetBytes() {
            return cacheLineBytes;
        }

        public long slotsOffsetBytes() {
            return slotsOffsetBytes;
        }

        public long slotStrideBytes() {
            return slotStrideBytes;
        }

        public long totalBytes() {
            return totalBytes;
        }

        public long slotOffsetBytes(int index) {
            if (index < 0 || index >= capacity) {
                throw new IndexOutOfBoundsException("slot index out of range: " + index);
            }
            return Math.addExact(slotsOffsetBytes,
                    Math.multiplyExact(slotStrideBytes, (long) index));
        }
    }

    /** Placement request, NOT evidence that the pages are resident on that node. */
    public static final class ArenaLayout {
        private final long requestedBytes;
        private final int alignmentBytes;
        private final int numaNode;
        private final long reservedBytes;

        private ArenaLayout(long requestedBytes, int alignmentBytes, int numaNode,
                            long reservedBytes) {
            this.requestedBytes = requestedBytes;
            this.alignmentBytes = alignmentBytes;
            this.numaNode = numaNode;
            this.reservedBytes = reservedBytes;
        }

        public long requestedBytes() {
            return requestedBytes;
        }

        public int requiredBaseAlignmentBytes() {
            return alignmentBytes;
        }

        public int requestedNumaNode() {
            return numaNode;
        }

        public long reservedBytes() {
            return reservedBytes;
        }
    }
}
