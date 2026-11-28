package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongUnaryOperator;
import java.util.concurrent.atomic.AtomicInteger;

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
    // The top bit marks a lifetime-pin ticket instead of a state handle.
    private static final long PIN_TAG = Long.MIN_VALUE;
    private static final int MAX_DOMAIN = 0x7F_FFFF;
    private static final int MAX_PINS = 64;
    private static final int MAX_GENERATION = 0xFF_FFFF;
    private static final AtomicInteger NEXT_DOMAIN = new AtomicInteger(1);
    private final int domain;
    private final long[] values;
    private final int[] generations;
    private final boolean[] live;
    private final int[] freeSlots;
    private final int[] pinGenerations = new int[MAX_PINS];
    private final boolean[] activePinSlots = new boolean[MAX_PINS];
    private final boolean[] startedPinSlots = new boolean[MAX_PINS];
    private int activePins;
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
        int allocated = NEXT_DOMAIN.getAndIncrement();
        if (allocated <= 0 || allocated > MAX_DOMAIN) {
            throw new IllegalStateException("region identity namespace exhausted");
        }
        domain = allocated;
        values = new long[capacity];
        generations = new int[capacity];
        live = new boolean[capacity];
        freeSlots = new int[capacity];
        Arrays.fill(generations, 1);
        Arrays.fill(pinGenerations, 1);
    }

    /** Handle: 23-bit nonreused domain, 24-bit generation, 16-bit slot.
     * The top tag bit is clear for state handles and set for lifetime pins.
     * Overflow fails closed rather than authorizing an ABA collision.
     * Handles and pin tickets are not cryptographic capabilities. */
    public synchronized long insert(long value) {
        requireAdmitting();
        final int slot;
        if (freeCount != 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot >= values.length) throw new IllegalStateException("region is full");
            slot = nextSlot++;
        }
        if (live[slot] || generations[slot] == 0 || generations[slot] > MAX_GENERATION) {
            throw new IllegalStateException("invalid reused region slot");
        }
        values[slot] = value;
        live[slot] = true;
        ++liveCount;
        return ((long) domain << 40)
                | ((long) generations[slot] << 16)
                | (long) slot;
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
     * Checked, in-place scalar addition without a callback/closure.
     * On signed overflow the existing value stays unchanged. This is
     * intentionally a primitive operation: no guard, boxed number, snapshot,
     * or user-supplied function is required on the successful hot path.
     */
    public synchronized long addExact(long handle, long delta) {
        int slot = requireLive(handle);
        long next = Math.addExact(values[slot], delta);
        values[slot] = next;
        return next;
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
            // Callbacks can reenter synchronized methods on the same JVM
            // carrier. If a callback initiated STOP via beginRetire() or
            // tryClose(), never commit a mutation after that monotonic fence.
            // Keep the original value untouched; the finalizer may zero it
            // only after all physical pins have retired.
            if (retiring || closed) {
                throw new IllegalStateException(
                        "region retired before mutation could commit");
            }
            values[slot] = next;
        } finally {
            mutating = false;
        }
    }

    /** Releases one scalar, zeroes it, and permanently rejects stale handles. */
    public synchronized void remove(long handle) {
        int slot = requireLive(handle);
        if (generations[slot] == MAX_GENERATION) {
            throw new IllegalStateException("region handle generation exhausted");
        }
        live[slot] = false;
        values[slot] = 0L;
        ++generations[slot];
        freeSlots[freeCount++] = slot;
        --liveCount;
    }

    /**
     * Admit one bounded, nonallocating lifetime pin before scheduling a task,
     * I/O operation or FFI callback. The trusted caller must retain the
     * primitive ticket until physical completion, then release it exactly
     * once. This is not a guest-visible borrow or permission grant.
     */
    public synchronized long pin() {
        requireAdmitting();
        for (int slot = 0; slot < MAX_PINS; slot++) {
            if (!activePinSlots[slot] && pinGenerations[slot] < MAX_GENERATION) {
                activePinSlots[slot] = true;
                startedPinSlots[slot] = false;
                ++activePins;
                return PIN_TAG | ((long) domain << 40)
                        | ((long) pinGenerations[slot] << 16) | (long) slot;
            }
        }
        throw new IllegalStateException("region lifetime pin budget exhausted");
    }

    /**
     * Linearize start of a pinned actor turn against beginRetire().
     * A pin acquired before STOP may sit in a dispatcher queue, but MUST NOT
     * execute guest work after STOP won this admission race. One ticket can
     * start at most one physical turn. This is trusted host bookkeeping,
     * not an authority to access the scalar state after retirement.
     */
    synchronized boolean claimPinnedTurn(long ticket) {
        int slot = requirePin(ticket);
        if (retiring || mutating || startedPinSlots[slot]) return false;
        startedPinSlots[slot] = true;
        return true;
    }

    /**
     * Release a completed pin even after admission has been retired.
     * Duplicate, foreign, stale and untyped tickets fail closed.
     * Cancellation requests alone must not call this method.
     */
    public synchronized void releasePin(long ticket) {
        int slot = requirePin(ticket);
        activePinSlots[slot] = false;
        startedPinSlots[slot] = false;
        ++pinGenerations[slot]; // saturated slot is never admitted again
        --activePins;
    }

    private int requirePin(long ticket) {
        int ticketDomain = (int) ((ticket >>> 40) & 0x7F_FFFFL);
        int slot = (int) (ticket & 0xFFFFL);
        int generation = (int) ((ticket >>> 16) & 0xFF_FFFFL);
        if (closed || (ticket & PIN_TAG) == 0 || ticketDomain != domain
                || slot >= MAX_PINS || generation == 0 || !activePinSlots[slot]
                || pinGenerations[slot] != generation) {
            throw new IllegalStateException("invalid, foreign or stale region lifetime pin");
        }
        return slot;
    }

    public synchronized int activePins() { return activePins; }

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
        if (mutating || activePins != 0) return false;
        Arrays.fill(values, 0L);
        Arrays.fill(live, false);
        Arrays.fill(freeSlots, 0);
        Arrays.fill(activePinSlots, false);
        Arrays.fill(startedPinSlots, false);
        liveCount = 0;
        freeCount = 0;
        activePins = 0;
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
        int handleDomain = (int) (handle >>> 40);
        int slot = (int) (handle & 0xFFFFL);
        int generation = (int) ((handle >>> 16) & 0xFF_FFFFL);
        if (handleDomain != domain || slot >= nextSlot || generation == 0) {
            throw new IllegalStateException("foreign or invalid region handle");
        }
        if (!live[slot] || generations[slot] != generation) {
            throw new IllegalStateException("foreign or stale region handle");
        }
        return slot;
    }
}
