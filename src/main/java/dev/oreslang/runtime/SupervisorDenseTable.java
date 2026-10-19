package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trusted-host reference table for dense supervisor child *metadata*, never actor heaps.
 * Exactly one physical layout owns the canonical values. No guest execution/borrows.
 * Synchronized operations are deliberate: this is a safe vertical slice, not SIMD proof.
 */
public final class SupervisorDenseTable implements AutoCloseable {
    public enum Layout { AOS, SOA, AOSOA }
    public enum Field { ACTOR_ID, STATE, RESTARTS, POLICY_REVISION, LAST_FAILURE_NANOS }
    public record Handle(long owner, int slot, long generation) {}
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();
    private static final int FIELDS = Field.values().length;
    private final long owner;
    private final int blockSize;
    private Layout layout;
    private long[] data;
    private int capacity;
    private int size;
    private int nextSlot;
    private int freeCount;
    private int[] slotByDense;
    private int[] denseBySlot;
    private long[] generationBySlot;
    private int[] freeSlots;
    private boolean closed;

    public SupervisorDenseTable(Layout layout, int initialCapacity, int blockSize) {
        this.layout = Objects.requireNonNull(layout, "layout");
        if (initialCapacity < 0 || blockSize < 8 || blockSize > 256
                || (blockSize & (blockSize - 1)) != 0)
            throw new IllegalArgumentException("capacity or AoSoA block size");
        long next = OWNER_SEQUENCE.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("owner identity exhausted");
        owner = next;
        this.blockSize = blockSize;
        capacity = initialCapacity;
        data = new long[dataLength(layout, capacity)];
        slotByDense = new int[capacity];
        denseBySlot = new int[capacity];
        Arrays.fill(denseBySlot, -1);
        generationBySlot = new long[capacity];
        freeSlots = new int[capacity];
    }

    private int dataLength(Layout kind, int rows) {
        int padded = kind == Layout.AOSOA
                ? Math.multiplyExact(Math.floorDiv(Math.addExact(rows, blockSize - 1), blockSize), blockSize)
                : rows;
        return Math.multiplyExact(padded, FIELDS);
    }

    private int offset(Layout kind, int cap, int row, int field) {
        return switch (kind) {
            case AOS -> Math.addExact(Math.multiplyExact(row, FIELDS), field);
            case SOA -> Math.addExact(Math.multiplyExact(field, cap), row);
            case AOSOA -> Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(row / blockSize, FIELDS), field), blockSize), row % blockSize);
        };
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("supervisor table closed");
    }

    private int dense(Handle h) {
        Objects.requireNonNull(h, "child handle");
        checkOpen();
        if (h.owner() != owner || h.slot() < 0 || h.slot() >= nextSlot
                || denseBySlot[h.slot()] < 0 || generationBySlot[h.slot()] != h.generation())
            throw new IllegalArgumentException("foreign, stale or deleted child handle");
        return denseBySlot[h.slot()];
    }

    public synchronized int size() { checkOpen(); return size; }
    public synchronized int capacity() { checkOpen(); return capacity; }
    public synchronized Layout layout() { checkOpen(); return layout; }
    public synchronized int blockSize() { checkOpen(); return blockSize; }

    private void ensureCapacity(int minimum) {
        if (capacity >= minimum) return;
        int grow = Math.max(minimum, Math.max(8, Math.addExact(capacity, Math.max(1, capacity / 2))));
        reallocate(layout, grow);
    }

    private void reallocate(Layout nextLayout, int nextCapacity) {
        long[] replacement = new long[dataLength(nextLayout, nextCapacity)];
        for (int row = 0; row < size; row++) {
            for (int field = 0; field < FIELDS; field++) {
                replacement[offset(nextLayout, nextCapacity, row, field)] =
                        data[offset(layout, capacity, row, field)];
            }
        }
        int[] nextDense = Arrays.copyOf(denseBySlot, nextCapacity);
        Arrays.fill(nextDense, denseBySlot.length, nextCapacity, -1);
        int[] nextSlotByDense = Arrays.copyOf(slotByDense, nextCapacity);
        long[] nextGenerations = Arrays.copyOf(generationBySlot, nextCapacity);
        int[] nextFree = Arrays.copyOf(freeSlots, nextCapacity);
        // Publish only after all copies and allocations succeed.
        data = replacement;
        slotByDense = nextSlotByDense;
        denseBySlot = nextDense;
        generationBySlot = nextGenerations;
        freeSlots = nextFree;
        capacity = nextCapacity;
        layout = nextLayout;
    }

    /** Checked, atomic layout conversion; handles survive, no raw views escape. */
    public synchronized void setLayout(Layout nextLayout) {
        checkOpen();
        Objects.requireNonNull(nextLayout, "layout");
        if (nextLayout != layout) reallocate(nextLayout, capacity);
    }

    public synchronized Handle add(long actorId, long state, long restarts, long policyRevision, long lastFailureNanos) {
        checkOpen();
        ensureCapacity(Math.max(Math.addExact(size, 1),
                freeCount > 0 ? 0 : Math.addExact(nextSlot, 1)));
        final int slot;
        if (freeCount > 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot == capacity) throw new IllegalStateException("slot capacity invariant");
            slot = nextSlot++;
            generationBySlot[slot] = 1;
        }
        int row = size++;
        slotByDense[row] = slot;
        denseBySlot[slot] = row;
        data[offset(layout, capacity, row, 0)] = actorId;
        data[offset(layout, capacity, row, 1)] = state;
        data[offset(layout, capacity, row, 2)] = restarts;
        data[offset(layout, capacity, row, 3)] = policyRevision;
        data[offset(layout, capacity, row, 4)] = lastFailureNanos;
        return new Handle(owner, slot, generationBySlot[slot]);
    }

    public synchronized long get(Handle h, Field f) {
        int row = dense(h);
        return data[offset(layout, capacity, row, Objects.requireNonNull(f).ordinal())];
    }

    public synchronized void set(Handle h, Field f, long value) {
        int row = dense(h);
        data[offset(layout, capacity, row, Objects.requireNonNull(f).ordinal())] = value;
    }

    /** Returns a detached snapshot, never a pointer into private actor memory. */
    public synchronized long[] snapshot(Handle h) {
        int row = dense(h);
        long[] out = new long[FIELDS];
        for (int f = 0; f < FIELDS; f++) out[f] = data[offset(layout, capacity, row, f)];
        return out;
    }

    public synchronized void eraseSwap(Handle h) {
        int row = dense(h);
        int tail = --size;
        int slot = h.slot();
        if (row != tail) {
            int tailSlot = slotByDense[tail];
            for (int f = 0; f < FIELDS; f++) {
                data[offset(layout, capacity, row, f)] = data[offset(layout, capacity, tail, f)];
            }
            slotByDense[row] = tailSlot;
            denseBySlot[tailSlot] = row;
        }
        for (int f = 0; f < FIELDS; f++) data[offset(layout, capacity, tail, f)] = 0L;
        slotByDense[tail] = 0;
        denseBySlot[slot] = -1;
        if (generationBySlot[slot] != Long.MAX_VALUE) {
            generationBySlot[slot]++;
            freeSlots[freeCount++] = slot;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Arrays.fill(data, 0);
        Arrays.fill(denseBySlot, -1);
        Arrays.fill(generationBySlot, 0L);
        // Release strong references to potentially million-row allocations.
        data = null;
        slotByDense = null;
        denseBySlot = null;
        generationBySlot = null;
        freeSlots = null;
        capacity = 0;
        size = 0;
        freeCount = 0;
    }
}
