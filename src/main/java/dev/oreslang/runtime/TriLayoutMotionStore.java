package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU-only, host-side reference for one, two, or three physically allocated
 * layouts of one logical fixed-width region. A single layout is authoritative
 * per epoch;
 * materializations are refreshed before reads or writes. This is NOT guest
 * language lowering, an actor-private heap, or a zero-GC production storage ABI.
 * No backing arrays or mutable borrows escape this reference implementation.
 */
public final class TriLayoutMotionStore implements AutoCloseable {
    public enum Layout { AOS, SOA, AOSOA }
    public record Motion(double x, double y, double vx, double vy) { }
    public record Handle(long storeId, int slot, long generation) { }

    private static final AtomicLong NEXT_STORE_ID = new AtomicLong(1);
    private static final int FIELDS = 4;
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;
    private final EnumSet<Layout> resident;
    private final long reservedPayloadBytes;
    private final long storeId;
    private final int capacity;
    private final int tile;
    private final double[] aos;
    private final double[][] soa;
    private final double[] aosoa;
    private final int[] slotByRow;
    private final int[] rowBySlot;
    private final long[] generations;
    private final int[] freeSlots;
    private final long[] materializedEpoch = {-1, -1, -1};
    private int freeCount;
    private int nextSlot;
    private int size;
    private long epoch = 1;
    private Layout authoritative;
    private boolean closed;

    /** Backward-compatible tri-resident reference constructor. */
    public TriLayoutMotionStore(int capacity, int tile) {
        this(capacity, tile, EnumSet.allOf(Layout.class), Layout.AOS, Long.MAX_VALUE);
    }

    /** Choose only the physical layouts needed by this region. */
    public TriLayoutMotionStore(int capacity, int tile, Set<Layout> layouts) {
        this(capacity, tile, layouts, firstLayout(layouts), Long.MAX_VALUE);
    }

    /**
     * Select the initial writer and a conservative primitive/handle payload
     * reservation budget. This estimate is NOT Java heap/RSS accounting: array
     * headers, runtime allocator fragmentation and snapshots are not included.
     *
     * There is no implicit layout fallback. A request for an absent layout
     * fails before it can mutate the selected authoritative representation.
     */
    public TriLayoutMotionStore(int capacity, int tile, Set<Layout> layouts,
                                Layout initialAuthority, long budgetBytes) {
        if (capacity <= 0 || tile < 2 || tile > 1024 || (tile & (tile - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be positive; tile must be power of two in [2,1024]");
        }
        if (capacity > MAX_ARRAY_LENGTH) {
            throw new IllegalArgumentException("region exceeds JVM array length bounds");
        }
        if (budgetBytes < 0) throw new IllegalArgumentException("negative region memory budget");
        if (layouts == null || layouts.isEmpty() || layouts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("nonempty nonnull layout residency is required");
        }
        this.resident = EnumSet.copyOf(layouts);
        if (initialAuthority == null || !resident.contains(initialAuthority)) {
            throw new IllegalArgumentException("initial authority must be resident");
        }
        int aosCells = 0;
        int aosoaCells = 0;
        long payload;
        try {
            if (resident.contains(Layout.AOS)) {
                aosCells = checkedCellCount(Math.multiplyExact((long) capacity, FIELDS));
            }
            if (resident.contains(Layout.AOSOA)) {
                long tiles = (long) capacity / tile + (capacity % tile == 0 ? 0 : 1);
                aosoaCells = checkedCellCount(Math.multiplyExact(
                        Math.multiplyExact(tiles, tile), FIELDS));
            }
            // Count actual resident primitive payloads and fixed row/slot tables.
            // SoA also owns a tiny reference table; its object headers are NOT
            // part of this lower-bound reservation.
            long cells = Math.addExact(aosCells, aosoaCells);
            if (resident.contains(Layout.SOA)) {
                cells = Math.addExact(cells, Math.multiplyExact((long) capacity, FIELDS));
            }
            long representation = Math.multiplyExact(cells, Double.BYTES);
            long handles = Math.multiplyExact((long) capacity,
                    Integer.BYTES * 3L + Long.BYTES);
            payload = Math.addExact(representation, Math.addExact(handles,
                    Math.multiplyExact((long) Layout.values().length, Long.BYTES)));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("layout reservation overflows", e);
        }
        if (payload > budgetBytes) {
            throw new IllegalArgumentException("region primitive payload exceeds budget");
        }
        // Validate the selected geometry/budget before consuming a unique ID.
        long next = NEXT_STORE_ID.getAndUpdate(v -> v == Long.MAX_VALUE ? v : v + 1);
        if (next == Long.MAX_VALUE) throw new IllegalStateException("store identity exhausted");
        this.storeId = next;
        this.capacity = capacity;
        this.tile = tile;
        this.reservedPayloadBytes = payload;
        this.authoritative = initialAuthority;
        this.aos = resident.contains(Layout.AOS) ? new double[aosCells] : null;
        this.soa = resident.contains(Layout.SOA) ? new double[FIELDS][capacity] : null;
        this.aosoa = resident.contains(Layout.AOSOA) ? new double[aosoaCells] : null;
        this.slotByRow = new int[capacity];
        this.rowBySlot = new int[capacity];
        this.generations = new long[capacity];
        this.freeSlots = new int[capacity];
        Arrays.fill(rowBySlot, -1);
        Arrays.fill(generations, 1);
        for (Layout layout : resident) materializedEpoch[layout.ordinal()] = epoch;
    }

    private static int checkedCellCount(long count) {
        if (count > MAX_ARRAY_LENGTH) {
            throw new IllegalArgumentException("physical layout exceeds JVM primitive array limit");
        }
        return (int) count;
    }

    private static Layout firstLayout(Set<Layout> layouts) {
        if (layouts == null || layouts.isEmpty() || layouts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("nonempty nonnull layout residency is required");
        }
        return EnumSet.copyOf(layouts).iterator().next();
    }

    /** Immutable copy: caller cannot change this store's physical residency. */
    public synchronized Set<Layout> residentLayouts() {
        requireOpen();
        return Set.copyOf(resident);
    }

    public synchronized boolean hasLayout(Layout layout) {
        requireOpen();
        return resident.contains(Objects.requireNonNull(layout, "layout"));
    }

    /** Primitive payload and fixed index tables only; not a total RSS estimate. */
    public synchronized long reservedPayloadBytes() {
        requireOpen();
        return reservedPayloadBytes;
    }

    public synchronized int size() { requireOpen(); return size; }
    public synchronized long epoch() { requireOpen(); return epoch; }
    public synchronized Layout authoritativeLayout() { requireOpen(); return authoritative; }
    public synchronized long materializedEpoch(Layout layout) {
        requireOpen();
        return materializedEpoch[requireResident(layout).ordinal()];
    }

    public synchronized Handle insert(Motion motion) {
        requireOpen();
        Objects.requireNonNull(motion, "motion");
        if (size == capacity || (freeCount == 0 && nextSlot == capacity)) {
            throw new IllegalStateException("region capacity or generation slots exhausted");
        }
        requireNextEpoch();
        int slot = freeCount != 0 ? freeSlots[--freeCount] : nextSlot++;
        write(authoritative, size, motion);
        slotByRow[size] = slot;
        rowBySlot[slot] = size;
        size++;
        advance(authoritative);
        return new Handle(storeId, slot, generations[slot]);
    }

    public synchronized void eraseSwap(Handle handle) {
        int row = checkedRow(handle);
        requireNextEpoch();
        int last = size - 1;
        if (row != last) {
            write(authoritative, row, read(authoritative, last));
            int relocatedSlot = slotByRow[last];
            slotByRow[row] = relocatedSlot;
            rowBySlot[relocatedSlot] = row;
        }
        size--;
        rowBySlot[handle.slot()] = -1;
        // Never recycle a max-generation slot: no ABA through generation wrap.
        if (generations[handle.slot()] != Long.MAX_VALUE) {
            generations[handle.slot()]++;
            freeSlots[freeCount++] = handle.slot();
        }
        advance(authoritative);
    }

    public synchronized Motion get(Handle handle, Layout layout) {
        int row = checkedRow(handle);
        ensure(layout);
        return read(layout, row); // Explicit one-row copy, not a mutable alias.
    }

    /**
     * Select the sole writable representation. This is an explicit versioned
     * authority transfer, not an extra writable copy; the selected resident
     * buffer is made current before publishing the new epoch.
     */
    public synchronized void selectLayout(Layout layout) {
        requireResident(layout);
        requireNextEpoch();
        ensure(layout);
        advance(layout);
    }

    public synchronized void set(Handle handle, Layout layout, Motion value) {
        int row = checkedRow(handle);
        Objects.requireNonNull(value, "value");
        requireResident(layout);
        requireNextEpoch();
        ensure(layout);
        write(layout, row, value);
        advance(layout);
    }

    /** One bulk update: reads velocity, modifies position, leaves velocity unchanged. */
    public synchronized void integrate(Layout layout, double dt) {
        requireOpen();
        if (!Double.isFinite(dt)) throw new IllegalArgumentException("dt must be finite");
        requireResident(layout);
        requireNextEpoch();
        for (int row = 0; row < size; row++) {
            double nx = getField(authoritative, row, 0) + getField(authoritative, row, 2) * dt;
            double ny = getField(authoritative, row, 1) + getField(authoritative, row, 3) * dt;
            if (!Double.isFinite(nx) || !Double.isFinite(ny)) {
                throw new ArithmeticException("integration would produce non-finite positions");
            }
        }
        ensure(layout);
        for (int row = 0; row < size; row++) {
            setField(layout, row, 0, getField(layout, row, 0) + getField(layout, row, 2) * dt);
            setField(layout, row, 1, getField(layout, row, 1) + getField(layout, row, 3) * dt);
        }
        advance(layout);
    }

    public synchronized double sumX(Layout layout) {
        requireOpen();
        ensure(layout);
        double total = 0;
        for (int row = 0; row < size; row++) total += getField(layout, row, 0);
        return total;
    }

    /** Explicitly allocates a detached value snapshot; backing arrays never escape. */
    public synchronized List<Motion> snapshot(Layout layout) {
        requireOpen();
        ensure(layout);
        var result = new ArrayList<Motion>(size);
        for (int row = 0; row < size; row++) result.add(read(layout, row));
        return List.copyOf(result);
    }

    private Layout requireResident(Layout requested) {
        requireOpen();
        Layout target = Objects.requireNonNull(requested, "layout");
        if (!resident.contains(target)) {
            throw new IllegalArgumentException("layout not resident: " + target);
        }
        return target;
    }

    private void ensure(Layout requested) {
        Layout target = requireResident(requested);
        if (materializedEpoch[target.ordinal()] == epoch) return;
        // Only a fully completed reconciliation publishes a current stamp.
        // An exception leaves the target marked stale; the next strong access
        // must retry conversion. Synchronized methods hide partial copies.
        for (int row = 0; row < size; row++) write(target, row, read(authoritative, row));
        materializedEpoch[target.ordinal()] = epoch;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("region closed");
    }

    private void requireNextEpoch() {
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("region epoch exhausted");
    }

    private void advance(Layout modified) {
        epoch++;
        authoritative = modified;
        materializedEpoch[modified.ordinal()] = epoch;
    }

    private int checkedRow(Handle handle) {
        requireOpen();
        Objects.requireNonNull(handle, "handle");
        if (handle.storeId() != storeId || handle.slot() < 0 || handle.slot() >= nextSlot) {
            throw new IllegalArgumentException("foreign or invalid handle");
        }
        int row = rowBySlot[handle.slot()];
        if (row < 0 || generations[handle.slot()] != handle.generation()) {
            throw new IllegalStateException("stale or deleted handle");
        }
        return row;
    }

    private Motion read(Layout layout, int row) {
        return new Motion(getField(layout, row, 0), getField(layout, row, 1),
                          getField(layout, row, 2), getField(layout, row, 3));
    }

    private void write(Layout layout, int row, Motion motion) {
        setField(layout, row, 0, motion.x());
        setField(layout, row, 1, motion.y());
        setField(layout, row, 2, motion.vx());
        setField(layout, row, 3, motion.vy());
    }

    private double getField(Layout layout, int row, int field) {
        return switch (layout) {
            case AOS -> aos[row * FIELDS + field];
            case SOA -> soa[field][row];
            case AOSOA -> aosoa[(row / tile * FIELDS + field) * tile + row % tile];
        };
    }

    private void setField(Layout layout, int row, int field, double value) {
        switch (layout) {
            case AOS -> aos[row * FIELDS + field] = value;
            case SOA -> soa[field][row] = value;
            case AOSOA -> aosoa[(row / tile * FIELDS + field) * tile + row % tile] = value;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        // Real arena-backed implementations must reclaim/pin under quiescence.
        // This Java heap reference deliberately exports no borrow or raw span.
    }
}
