package dev.oreslang.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prototype unmanaged CONTROL-plane storage, separate from actor-private heaps.
 * Host API witnesses are 64 bits (issuer:32, Ref32:32); control heap fields
 * store exactly four-byte tokens. Never treat the host witness as a guest pointer.
 *
 * Bounded host primitive: ActorRuntime may opt in to one native root counter.
 * Managed scheduler queues and compiled Atto guest object lowering remain outside this pilot.
 * Allocates the arena/segment host wrappers once at boot, not per operation.
 */
final class ControlPlaneArena32 implements AutoCloseable {
    public static final String ABI = "control_generation12_slot20_v1";
    public static final int RECORD_BYTES = 32;
    private static final int SLOT_BITS = 20;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int MAX_GENERATION = (1 << (32 - SLOT_BITS)) - 1;
    private static final int META_BYTES = 12;
    private static final int NO_FREE = 0;
    private static final AtomicLong NEXT_ISSUER = new AtomicLong();
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Arena arena;
    private final MemorySegment records;
    private final MemorySegment slots;
    private final int capacity;
    private final long issuer;
    private int nextSlot;
    private int freeHead;
    private boolean closed;

    /**
     * Reserve an issuer once, without ever incrementing beyond the unsigned
     * 32-bit identity space. Synthetic counters can test rollover boundaries
     * without burning process-global issuer IDs.
     */
    static long reserveIssuer(AtomicLong sequence) {
        for (;;) {
            long seen = sequence.get();
            if (seen < 0L || seen >= 0xffff_ffffL)
                throw new IllegalStateException("control issuer IDs exhausted; cannot recycle");
            long next = seen + 1L;
            if (sequence.compareAndSet(seen, next)) return next;
        }
    }

    public ControlPlaneArena32(int capacity) {
        if (capacity <= 0 || capacity > SLOT_MASK)
            throw new IllegalArgumentException("control slot quota outside Ref32 capacity");
        final long id = reserveIssuer(NEXT_ISSUER);
        this.issuer = id;
        this.capacity = capacity;
        arena = Arena.ofShared();
        try {
            records = arena.allocate(Math.multiplyExact((long) capacity, RECORD_BYTES), 8);
            slots = arena.allocate(Math.multiplyExact((long) capacity, META_BYTES), 4);
            records.fill((byte) 0);
            slots.fill((byte) 0);
            for (int i = 0; i < capacity; i++) setMeta(i, 0, 1);
        } catch (RuntimeException | Error error) {
            arena.close();
            throw error;
        }
    }

    // A signed Java long carries an immutable, issuer-qualified host witness.
    // Only its low word is written into an internal 32-bit reference field.
    private long witness(int ref) { return (issuer << 32) | Integer.toUnsignedLong(ref); }
    private int meta(int slot, int field) { return slots.get(U32, (long) slot * META_BYTES + field); }
    private void setMeta(int slot, int field, int value) {
        slots.set(U32, (long) slot * META_BYTES + field, value);
    }
    private long offset(int slot, int field) { return (long) slot * RECORD_BYTES + field; }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("control heap already closed");
    }
    private int validate(long handle) {
        ensureOpen();
        if ((handle >>> 32) != issuer)
            throw new IllegalArgumentException("foreign actor/control domain witness");
        int ref = (int) handle;
        int slot = (ref & SLOT_MASK) - 1;
        int generation = ref >>> SLOT_BITS;
        if (generation == 0 || slot < 0 || slot >= capacity || meta(slot, 4) == 0
                || meta(slot, 0) != generation)
            throw new IllegalStateException("null, forged or stale ControlRef32");
        return slot;
    }
    public synchronized long allocate() {
        ensureOpen();
        int slot;
        if (freeHead != NO_FREE) {
            slot = freeHead - 1;
            freeHead = meta(slot, 8);
        } else {
            if (nextSlot == capacity) throw new IllegalStateException("control heap quota exhausted");
            slot = nextSlot++;
        }
        if (meta(slot, 4) != 0 || meta(slot, 0) == 0)
            throw new IllegalStateException("corrupt or permanently retired control slot");
        setMeta(slot, 4, 1);
        setMeta(slot, 8, 0);
        int word = (meta(slot, 0) << SLOT_BITS) | (slot + 1);
        return witness(word);
    }
    public synchronized void drop(long handle) {
        int slot = validate(handle);
        // Avoid creating a MemorySegment slice/wrapper on the per-record drop path.
        for (int field = 0; field < RECORD_BYTES; field += Long.BYTES)
            records.set(I64, offset(slot, field), 0L);
        setMeta(slot, 4, 0);
        int generation = meta(slot, 0);
        if (generation == MAX_GENERATION) {
            setMeta(slot, 0, 0); // exhaust, never wrap and revive an old handle
        } else {
            setMeta(slot, 0, generation + 1);
            setMeta(slot, 8, freeHead);
            freeHead = slot + 1;
        }
    }
    public synchronized void writeI64(long handle, int field, long value) {
        int slot = validate(handle);
        if (field != 0 && field != 8)
            throw new IllegalArgumentException("only typed i64 fields 0 and 8");
        records.set(I64, offset(slot, field), value);
    }
    public synchronized long readI64(long handle, int field) {
        int slot = validate(handle);
        if (field != 0 && field != 8)
            throw new IllegalArgumentException("only typed i64 fields 0 and 8");
        return records.get(I64, offset(slot, field));
    }
    /**
     * Store one 128-bit scalar atomically with respect to all other operations
     * on this control arena. Low/high limbs are little-endian. No managed
     * per-operation buffer; the compiler must validate the 128-bit field type.
     * Do not mix this field with separate i64 writes in typed guest lowering.
     */
    synchronized void writeI128(long handle, long low, long high) {
        int slot = validate(handle);
        records.set(I64, offset(slot, 0), low);
        records.set(I64, offset(slot, 8), high);
    }

    /** Read two limbs into a caller-owned reusable buffer; zero allocations. */
    synchronized void readI128(long handle, long[] limbs) {
        if (limbs == null || limbs.length != 2)
            throw new IllegalArgumentException("i128 output must be two caller-owned limbs");
        int slot = validate(handle);
        limbs[0] = records.get(I64, offset(slot, 0));
        limbs[1] = records.get(I64, offset(slot, 8));
    }

    public synchronized void writeRef32(long parent, long child) {
        int slot = validate(parent);
        if (child != 0L) validate(child); // child must belong to this exact control arena
        records.set(U32, offset(slot, 16), (int) child);
    }
    public synchronized long readRef32(long parent) {
        int slot = validate(parent);
        int word = records.get(U32, offset(slot, 16));
        if (word == 0) return 0L;
        long child = witness(word);
        validate(child);
        return child;
    }
    public synchronized int capacity() { return capacity; }
    @Override public synchronized void close() {
        if (!closed) {
            closed = true; // prohibit reentrant admissions before physically releasing memory
            records.fill((byte) 0);
            slots.fill((byte) 0);
            arena.close();
        }
    }
}
