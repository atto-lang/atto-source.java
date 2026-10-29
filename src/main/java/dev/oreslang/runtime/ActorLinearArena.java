package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent actor-owned native byte arena for the JVM/Graal backend.
 *
 * <p>The backing bytes are one direct buffer allocated at actor construction.
 * Guest allocations return primitive long handles and use preallocated metadata
 * arrays, so successful steady-state allocate/read/write/remove operations do
 * not create per-guest Java objects. This is a <em>minimum-GC</em> substrate,
 * not a zero-JVM-GC claim: the ByteBuffer control object and its cleaner remain
 * JVM-managed and physical native release is not deterministic.</p>
 *
 * <p>The borrow checker/compiler is expected to emit {@link #remove(long)} at
 * the last owner/scope exit and {@link #close()} only after actor, async, pin
 * and FFI quiescence. No raw native pointer or ByteBuffer slice is exposed.</p>
 */
public final class ActorLinearArena implements AutoCloseable {
    public static final long INVALID_HANDLE = 0L;
    private static final int MAX_SLOTS = 65_535;
    private static final int SLOT_BITS = 16;
    private static final long SLOT_MASK = (1L << SLOT_BITS) - 1L;
    private static final long MAX_SEQUENCE = (1L << (64 - SLOT_BITS)) - 1L;
    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong(1L);

    private final ByteBuffer memory;
    private final int[] offsets;
    private final int[] lengths;
    private final long[] tokens;
    private final boolean[] live;
    private final int[] freeSlots;
    // Compiler-owned cleanup stack. Scope marks are primitive ints; tracking
    // uses this preallocated array so lexical cleanup itself creates no Java
    // objects or collections on the hot path.
    private final long[] ownedCleanup;
    // Each entry retains an admission serial independent of its current stack
    // index. A lexical mark must remain valid when an older owner is moved or
    // dropped; array length alone is not a stable lifetime checkpoint.
    private final int[] ownedCleanupSerial;
    private int ownedCleanupCount;
    private int lastCleanupSerial;
    private int cursor;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private boolean closed;

    public ActorLinearArena(int bytes, int slots) {
        if (bytes <= 0) throw new IllegalArgumentException("positive arena byte capacity required");
        if (slots <= 0 || slots > MAX_SLOTS)
            throw new IllegalArgumentException("bounded positive slot capacity required");
        memory = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        offsets = new int[slots];
        lengths = new int[slots];
        tokens = new long[slots];
        live = new boolean[slots];
        freeSlots = new int[slots];
        ownedCleanup = new long[slots];
        ownedCleanupSerial = new int[slots];
    }

    public int capacityBytes() { return memory.capacity(); }
    public int usedBytes() { return cursor; }
    public int liveAllocations() { return liveCount; }
    public boolean closed() { return closed; }

    /**
     * Allocate without creating a per-allocation Java handle object.
     * Returns zero on capacity/alignment exhaustion.
     */
    public long allocate(int bytes, int alignment) {
        if (closed || bytes <= 0 || alignment <= 0 || (alignment & (alignment - 1)) != 0
                || alignment > 64 || (freeCount == 0 && nextSlot >= live.length)) {
            return INVALID_HANDLE;
        }
        long alignedLong = ((long) cursor + alignment - 1L) & -((long) alignment);
        if (alignedLong > Integer.MAX_VALUE) return INVALID_HANDLE;
        int aligned = (int) alignedLong;
        if (aligned > memory.capacity() || bytes > memory.capacity() - aligned)
            return INVALID_HANDLE;

        long sequence = nextSequence();
        if (sequence == 0) return INVALID_HANDLE;
        int slot = freeCount == 0 ? nextSlot++ : freeSlots[--freeCount];
        long token = (sequence << SLOT_BITS) | (slot + 1L);
        offsets[slot] = aligned;
        lengths[slot] = bytes;
        tokens[slot] = token;
        live[slot] = true;
        cursor = aligned + bytes;
        liveCount++;
        return token;
    }

    public long allocate(int bytes) { return allocate(bytes, 8); }

    /** Primitive lexical-scope checkpoint emitted by ownership lowering. */
    public int cleanupMark() {
        if (closed) throw new IllegalStateException("actor arena is closed");
        return lastCleanupSerial;
    }

    /**
     * Allocate a unique owner and arm deterministic scope cleanup without
     * allocating a Java cleanup object.
     */
    public long allocateOwned(int bytes, int alignment) {
        // Never wrap a lexical checkpoint: a wrapped serial could silently
        // spare inner-scope owners or free owners from an enclosing scope.
        if (lastCleanupSerial == Integer.MAX_VALUE) return INVALID_HANDLE;
        long handle = allocate(bytes, alignment);
        if (handle == INVALID_HANDLE) return INVALID_HANDLE;
        if (ownedCleanupCount >= ownedCleanup.length) {
            // Live slots and cleanup capacity are equal, so this indicates a
            // runtime/compiler invariant violation rather than normal pressure.
            if (!remove(handle)) throw new IllegalStateException("failed to roll back untracked owner");
            throw new IllegalStateException("actor arena cleanup stack exhausted");
        }
        ownedCleanup[ownedCleanupCount] = handle;
        ownedCleanupSerial[ownedCleanupCount++] = ++lastCleanupSerial;
        return handle;
    }

    public long allocateOwned(int bytes) { return allocateOwned(bytes, 8); }

    /** Disarm lexical cleanup for a move/ownership transfer. */
    public boolean releaseOwned(long handle) {
        int index = cleanupIndex(handle);
        if (index < 0) return false;
        untrackAt(index);
        return true;
    }

    /** Adopt a live transferred owner into the current lexical scope. */
    public boolean adoptOwned(long handle) {
        if (validSlot(handle) < 0 || cleanupIndex(handle) >= 0
                || ownedCleanupCount >= ownedCleanup.length
                || lastCleanupSerial == Integer.MAX_VALUE) return false;
        ownedCleanup[ownedCleanupCount] = handle;
        ownedCleanupSerial[ownedCleanupCount++] = ++lastCleanupSerial;
        return true;
    }

    /** Drop one compiler-tracked owner immediately and exactly once. */
    public boolean dropOwned(long handle) {
        int index = cleanupIndex(handle);
        if (index < 0 || !remove(handle)) return false;
        untrackAt(index);
        return true;
    }

    /**
     * Reverse-drop every still-owned value created/adopted after mark.
     * A stale handle is reported but cannot suppress cleanup of later owners.
     */
    public boolean cleanupTo(int mark) {
        if (closed) throw new IllegalStateException("actor arena is closed");
        if (mark < 0 || mark > lastCleanupSerial)
            throw new IllegalArgumentException("invalid actor arena cleanup mark");
        boolean ok = true;
        while (ownedCleanupCount > 0 && ownedCleanupSerial[ownedCleanupCount - 1] > mark) {
            long handle = ownedCleanup[--ownedCleanupCount];
            ownedCleanup[ownedCleanupCount] = INVALID_HANDLE;
            ownedCleanupSerial[ownedCleanupCount] = 0;
            if (!remove(handle)) ok = false;
        }
        return ok;
    }

    public boolean remove(long handle) {
        int slot = validSlot(handle);
        if (slot < 0) return false;
        zero(offsets[slot], lengths[slot]);
        live[slot] = false;
        tokens[slot] = 0L;
        offsets[slot] = 0;
        lengths[slot] = 0;
        freeSlots[freeCount++] = slot;
        liveCount--;
        if (liveCount == 0) {
            zero(0, cursor);
            cursor = 0;
        }
        return true;
    }

    public boolean putLong(long handle, int relativeOffset, long value) {
        int slot = validSlot(handle);
        if (slot < 0 || relativeOffset < 0 || relativeOffset > lengths[slot] - Long.BYTES)
            return false;
        memory.putLong(offsets[slot] + relativeOffset, value);
        return true;
    }

    public long getLong(long handle, int relativeOffset) {
        int slot = validSlot(handle);
        if (slot < 0 || relativeOffset < 0 || relativeOffset > lengths[slot] - Long.BYTES)
            throw new IllegalStateException("invalid, stale or out-of-bounds actor arena handle");
        return memory.getLong(offsets[slot] + relativeOffset);
    }

    public boolean putByte(long handle, int relativeOffset, byte value) {
        int slot = validSlot(handle);
        if (slot < 0 || relativeOffset < 0 || relativeOffset >= lengths[slot])
            return false;
        memory.put(offsets[slot] + relativeOffset, value);
        return true;
    }

    public byte getByte(long handle, int relativeOffset) {
        int slot = validSlot(handle);
        if (slot < 0 || relativeOffset < 0 || relativeOffset >= lengths[slot])
            throw new IllegalStateException("invalid, stale or out-of-bounds actor arena handle");
        return memory.get(offsets[slot] + relativeOffset);
    }

    @Override
    public void close() {
        if (closed) return;
        zero(0, cursor);
        for (int i = 0; i < nextSlot; i++) {
            live[i] = false;
            tokens[i] = 0L;
            offsets[i] = 0;
            lengths[i] = 0;
        }
        java.util.Arrays.fill(ownedCleanup, INVALID_HANDLE);
        java.util.Arrays.fill(ownedCleanupSerial, 0);
        ownedCleanupCount = 0;
        cursor = 0;
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
        closed = true;
    }

    private int cleanupIndex(long handle) {
        for (int i = 0; i < ownedCleanupCount; i++) {
            if (ownedCleanup[i] == handle) return i;
        }
        return -1;
    }

    private void untrackAt(int index) {
        int moved = ownedCleanupCount - index - 1;
        if (moved > 0) {
            System.arraycopy(ownedCleanup, index + 1, ownedCleanup, index, moved);
            System.arraycopy(ownedCleanupSerial, index + 1, ownedCleanupSerial, index, moved);
        }
        ownedCleanup[--ownedCleanupCount] = INVALID_HANDLE;
        ownedCleanupSerial[ownedCleanupCount] = 0;
    }

    private int validSlot(long handle) {
        if (closed || handle == INVALID_HANDLE) return -1;
        int encoded = (int) (handle & SLOT_MASK);
        if (encoded <= 0) return -1;
        int slot = encoded - 1;
        return slot < nextSlot && live[slot] && tokens[slot] == handle ? slot : -1;
    }

    private void zero(int start, int length) {
        for (int i = 0; i < length; i++) memory.put(start + i, (byte) 0);
    }

    private static long nextSequence() {
        for (;;) {
            long current = NEXT_SEQUENCE.get();
            if (current <= 0 || current > MAX_SEQUENCE) return 0L;
            if (NEXT_SEQUENCE.compareAndSet(current, current + 1L)) return current;
        }
    }
}
