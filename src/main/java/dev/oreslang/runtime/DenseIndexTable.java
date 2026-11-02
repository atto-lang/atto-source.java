package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Bounded identity and generation sidecar for compact 32-bit dense indices.
 *
 * <p>The hot representation is still a single unsigned 32-bit index+1 word.
 * This table owns the out-of-line incarnation metadata needed when an identity
 * survives a row deletion/reuse, table clear, persistence boundary or transfer
 * between components. It is not an ActorRef and does not authorize access to
 * actor memory; actor confinement and borrow rules remain independently
 * required.</p>
 *
 * <p>All public operations are synchronized so a validation cannot race a
 * remove/reuse within this metadata object. Storage access still belongs to
 * the owning table/actor. A successful resolve is a momentary validation, not
 * a lease that permits use after the caller's ownership scope ends.</p>
 */
public final class DenseIndexTable<TTag> implements AutoCloseable {
    private static final int MAX_CAPACITY = 1_000_000;

    private final Object identity = new Object();
    private final boolean[] live;
    private final long[] generations;
    private final int[] freeSlots;
    private final int capacity;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private long epoch = 1;
    private boolean closed;

    public DenseIndexTable(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("dense index table capacity outside bounded range");
        }
        this.capacity = capacity;
        this.live = new boolean[capacity];
        this.generations = new long[capacity];
        this.freeSlots = new int[capacity];
        Arrays.fill(generations, 1L);
    }

    /** Allocate the next available row identity. */
    public synchronized DenseIndex32<TTag> allocate() {
        ensureOpen();
        final int slot;
        if (freeCount != 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot == capacity) {
                throw new IllegalStateException("dense index table capacity exhausted");
            }
            slot = nextSlot++;
        }
        if (live[slot] || generations[slot] <= 0) {
            throw new IllegalStateException("dense index table free-list corruption");
        }
        live[slot] = true;
        liveCount++;
        return new DenseIndex32<>(identity, epoch,
                ManagedReferencePolicy.encodeDenseIndex32(slot), generations[slot]);
    }

    /** Validate exact table identity, current epoch, slot and generation. */
    public synchronized boolean isLive(DenseIndex32<TTag> handle) {
        if (closed || handle == null || !handle.belongsTo(identity)
                || handle.tableEpoch() != epoch) {
            return false;
        }
        final long logicalIndex = handle.index();
        if (logicalIndex < 0 || logicalIndex >= nextSlot) return false;
        int slot = (int) logicalIndex;
        return live[slot] && generations[slot] == handle.generation();
    }

    /** Return the compact word only after validating its out-of-line identity. */
    public synchronized OptionalInt checkedStorageWord(DenseIndex32<TTag> handle) {
        return isLive(handle) ? OptionalInt.of(handle.encodedWord()) : OptionalInt.empty();
    }

    /**
     * Retire one identity. A generation at the terminal value is permanently
     * retired rather than wrapped and made capable of reviving an old handle.
     */
    public synchronized boolean remove(DenseIndex32<TTag> handle) {
        if (!isLive(handle)) return false;
        int slot = (int) handle.index();
        live[slot] = false;
        liveCount--;
        if (generations[slot] != Long.MAX_VALUE) {
            generations[slot]++;
            freeSlots[freeCount++] = slot;
        }
        return true;
    }

    /**
     * Invalidate all outstanding handles with one epoch transition.
     * Epoch exhaustion permanently closes the table instead of wrapping.
     */
    public synchronized void clear() {
        ensureOpen();
        if (epoch == Long.MAX_VALUE) {
            closeInternal();
            throw new IllegalStateException("dense index table epoch exhausted; table retired");
        }
        epoch++;
        Arrays.fill(live, false);
        Arrays.fill(generations, 1L);
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
    }

    public synchronized int size() {
        return liveCount;
    }

    public int capacity() {
        return capacity;
    }

    public synchronized long epoch() {
        ensureOpen();
        return epoch;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closeInternal();
    }

    private void closeInternal() {
        closed = true;
        Arrays.fill(live, false);
        Arrays.fill(generations, 0L);
        Arrays.fill(freeSlots, 0);
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("dense index table is closed");
    }
}
