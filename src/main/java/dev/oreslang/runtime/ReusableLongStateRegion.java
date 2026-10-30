package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongUnaryOperator;

/**
 * Preallocated, bounded host scalar region with no mandatory managed object
 * allocation per read, write, or mutation. Handles are long values, not
 * allocated guards. An actor/task must still prove exclusive access and
 * suspension-safe lifetimes before compiling guest state into this class.
 *
 * <p>Synchronization serializes this Java host primitive; no callback may
 * reenter it. It is NOT a concurrent-read container, a native allocator, a
 * general guest object heap, deterministic physical JVM reclamation or a
 * guarantee that a caller's lambda does not allocate. External async/FFI
 * leases require a separate physical quiescence protocol.</p>
 */
public final class ReusableLongStateRegion implements AutoCloseable {
    private static final int MAX_CAPACITY = 65_536;
    private final long[] values;
    private final int[] generations;
    private final boolean[] live;
    private final int[] freeSlots;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private boolean mutating;
    private boolean retiring;
    private boolean closed;

    public ReusableLongStateRegion(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity outside bounded region");
        }
        values = new long[capacity];
        generations = new int[capacity];
        live = new boolean[capacity];
        freeSlots = new int[capacity];
        Arrays.fill(generations, 1);
    }

    /** Generation in high 32 bits and slot in low 32 bits. Neither is a pointer. */
    public synchronized long insert(long value) {
        requireAdmitting();
        final int slot;
        if (freeCount != 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot >= values.length) throw new IllegalStateException("region is full");
            slot = nextSlot++;
        }
        if (live[slot] || generations[slot] == 0) {
            throw new IllegalStateException("invalid reused region slot");
        }
        values[slot] = value;
        live[slot] = true;
        ++liveCount;
        return (Integer.toUnsignedLong(generations[slot]) << 32)
                | Integer.toUnsignedLong(slot);
    }

    /** Primitive scalar read; produces no borrowed Java wrapper or view. */
    public synchronized long read(long handle) {
        return values[requireLive(handle)];
    }

    /** In-place scalar write, without a snapshot or callback allocation. */
    public synchronized void write(long handle, long value) {
        values[requireLive(handle)] = value;
    }

    /**
     * In-place read/modify/write. The caller may reuse an existing operator.
     * No token is allocated per call. While the operator executes, nested
     * reads/writes/releases/closure are denied; exception unwind clears the
     * gate. This host API cannot prove guest lexical borrow nonescape.
     */
    public synchronized void update(long handle, LongUnaryOperator operation) {
        Objects.requireNonNull(operation, "operation");
        int slot = requireLive(handle);
        mutating = true;
        try {
            long next = operation.applyAsLong(values[slot]);
            values[slot] = next;
        } finally {
            mutating = false;
        }
    }

    /** Releases one scalar, zeroes it, and permanently rejects stale handles. */
    public synchronized void remove(long handle) {
        int slot = requireLive(handle);
        if (generations[slot] == -1) {
            throw new IllegalStateException("region handle generation exhausted");
        }
        live[slot] = false;
        values[slot] = 0L;
        ++generations[slot];
        freeSlots[freeCount++] = slot;
        --liveCount;
    }

    /** Monotonic admission barrier; does not prove async cancellation drained. */
    public synchronized void beginRetire() {
        retiring = true;
    }

    /**
     * Clear all owned scalar storage after synchronized accesses finish.
     * Java arrays remain JVM-managed and are physically reclaimed by GC.
     * The caller must separately fence outstanding async/FFI producer leases.
     */
    public synchronized boolean tryClose() {
        retiring = true;
        if (closed) return true;
        if (mutating) return false;
        Arrays.fill(values, 0L);
        Arrays.fill(live, false);
        Arrays.fill(freeSlots, 0);
        liveCount = 0;
        freeCount = 0;
        closed = true;
        return true;
    }

    @Override
    public synchronized void close() {
        if (!tryClose()) throw new IllegalStateException("mutation still active");
    }

    public synchronized int size() { return liveCount; }
    public synchronized boolean isClosed() { return closed; }
    public synchronized boolean isRetiring() { return retiring; }

    private void requireAdmitting() {
        if (retiring || closed || mutating) {
            throw new IllegalStateException("region does not admit new access");
        }
    }

    private int requireLive(long handle) {
        requireAdmitting();
        long rawSlot = handle & 0xFFFF_FFFFL;
        if (rawSlot >= nextSlot) throw new IllegalStateException("invalid region slot");
        int slot = (int) rawSlot;
        if (!live[slot] || generations[slot] != (int) (handle >>> 32)) {
            throw new IllegalStateException("foreign or stale region handle");
        }
        return slot;
    }
}
