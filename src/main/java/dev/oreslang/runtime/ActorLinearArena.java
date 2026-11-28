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
    private static final long MAX_GENERATION = (1L << (64 - SLOT_BITS)) - 1L;
    /** Golden-ratio stride so arenas start from well-separated handle salts. */
    private static final long SALT_STRIDE = 0x9E3779B97F4A7C15L;
    private static final AtomicLong NEXT_ARENA_SALT = new AtomicLong(SALT_STRIDE);
    /** JVM metadata per slot: every parallel array below, for quota accounting. */
    static final int METADATA_BYTES_PER_SLOT = 3 * Integer.BYTES + Long.BYTES + 1 + Integer.BYTES
            + 2 * Long.BYTES + 3 * Integer.BYTES;

    // Mixed into handles so a token from one arena does not validate in another,
    // without any shared state on the allocation path. This catches confused
    // handles; authority still comes from the actor-owner check on the facade.
    private final long handleSalt;
    private final ByteBuffer memory;
    private final int[] offsets;
    private final int[] reservationStarts;
    private final int[] lengths;
    // Holds the current token while live, and the last issued token while free so
    // the slot generation survives removal.
    private final long[] tokens;
    private final boolean[] live;
    private final int[] freeSlots;
    // Preallocated intrusive ownership stack. Nodes are distinct from storage
    // slots: an out-of-band remove may leave a stale owner node behind until
    // cleanupTo reports the ownership violation. All normal owner transfers,
    // early drops and lexical cleanup use O(1) node operations.
    private final long[] ownedCleanup;
    private final long[] ownedCleanupSerial;
    private final int[] cleanupPrev;
    private final int[] cleanupNext;
    private final int[] trackedNodeBySlot;
    private int cleanupTail = -1;
    private int freeCleanupNode = -1;
    private int nextCleanupNode;
    private int ownedCleanupCount;
    private long lastCleanupSerial;
    private int cursor;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private boolean closed;

    public ActorLinearArena(int bytes, int slots) {
        if (bytes <= 0) throw new IllegalArgumentException("positive arena byte capacity required");
        if (slots <= 0 || slots > MAX_SLOTS)
            throw new IllegalArgumentException("bounded positive slot capacity required");
        handleSalt = NEXT_ARENA_SALT.getAndAdd(SALT_STRIDE) & MAX_GENERATION;
        memory = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        offsets = new int[slots];
        reservationStarts = new int[slots];
        lengths = new int[slots];
        tokens = new long[slots];
        live = new boolean[slots];
        freeSlots = new int[slots];
        ownedCleanup = new long[slots];
        ownedCleanupSerial = new long[slots];
        cleanupPrev = new int[slots];
        cleanupNext = new int[slots];
        trackedNodeBySlot = new int[slots];
        java.util.Arrays.fill(trackedNodeBySlot, -1);
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

        int slot = freeCount == 0 ? nextSlot++ : freeSlots[--freeCount];
        long generation = generationOf(tokens[slot]) + 1L;
        // A previous owner may have been removed out of band. Its cleanup node
        // remains in the list for error reporting, but cannot authorize a new
        // allocation that reuses this storage slot.
        trackedNodeBySlot[slot] = -1;
        long token = ((generation ^ handleSalt) << SLOT_BITS) | (slot + 1L);
        offsets[slot] = aligned;
        reservationStarts[slot] = cursor;
        lengths[slot] = bytes;
        tokens[slot] = token;
        live[slot] = true;
        cursor = aligned + bytes;
        liveCount++;
        return token;
    }

    public long allocate(int bytes) { return allocate(bytes, 8); }

    /** Primitive lexical-scope checkpoint emitted by ownership lowering. */
    public long cleanupMark() {
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
        if (lastCleanupSerial == Long.MAX_VALUE) return INVALID_HANDLE;
        long handle = allocate(bytes, alignment);
        if (handle == INVALID_HANDLE) return INVALID_HANDLE;
        if (!trackOwner(handle)) {
            // A stale owner removed outside compiler lowering may still occupy
            // a cleanup node; never overwrite that node or silently skip it.
            if (!remove(handle)) throw new IllegalStateException("failed to roll back untracked owner");
            throw new IllegalStateException("actor arena cleanup stack exhausted");
        }
        return handle;
    }

    public long allocateOwned(int bytes) { return allocateOwned(bytes, 8); }

    /** Disarm lexical cleanup for a move/ownership transfer. */
    public boolean releaseOwned(long handle) {
        int node = trackedNode(handle);
        if (node < 0) return false;
        unlinkOwner(node);
        return true;
    }

    /** Adopt a live transferred owner into the current lexical scope. */
    public boolean adoptOwned(long handle) {
        if (lastCleanupSerial == Long.MAX_VALUE || trackedNode(handle) >= 0)
            return false;
        return trackOwner(handle);
    }

    /** Drop one compiler-tracked owner immediately and exactly once. */
    public boolean dropOwned(long handle) {
        int node = trackedNode(handle);
        if (node < 0 || !remove(handle)) return false;
        unlinkOwner(node);
        return true;
    }

    /**
     * Reverse-drop every still-owned value created/adopted after mark.
     * A stale handle is reported but cannot suppress cleanup of later owners.
     */
    public boolean cleanupTo(long mark) {
        if (closed) throw new IllegalStateException("actor arena is closed");
        if (mark < 0 || mark > lastCleanupSerial)
            throw new IllegalArgumentException("invalid actor arena cleanup mark");
        boolean ok = true;
        while (cleanupTail >= 0 && ownedCleanupSerial[cleanupTail] > mark) {
            int node = cleanupTail;
            long handle = ownedCleanup[node];
            unlinkOwner(node);
            if (!remove(handle)) ok = false;
        }
        return ok;
    }

    public boolean remove(long handle) {
        int slot = validSlot(handle);
        if (slot < 0) return false;
        int reservationStart = reservationStarts[slot];
        int reservationEnd = offsets[slot] + lengths[slot];
        // Scrub the complete reservation, including alignment padding,
        // before any future allocation may reuse these bytes.
        zero(reservationStart, reservationEnd - reservationStart);
        live[slot] = false;
        offsets[slot] = 0;
        reservationStarts[slot] = 0;
        lengths[slot] = 0;
        // A slot whose generation space is spent is retired, never wrapped, so a
        // stale handle can never validate again.
        if (generationOf(handle) != MAX_GENERATION) freeSlots[freeCount++] = slot;
        liveCount--;
        if (liveCount == 0) {
            zero(0, cursor);
            cursor = 0;
        } else if (reservationEnd == cursor) {
            // Reclaim the topmost bump reservation (including alignment
            // padding) immediately. Persistent actor fields can survive while
            // turn temporaries reuse this space indefinitely. A non-topmost
            // removal still leaves a bounded hole until full region reset.
            cursor = reservationStart;
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
            reservationStarts[i] = 0;
            lengths[i] = 0;
        }
        java.util.Arrays.fill(ownedCleanup, INVALID_HANDLE);
        java.util.Arrays.fill(ownedCleanupSerial, 0L);
        java.util.Arrays.fill(trackedNodeBySlot, -1);
        ownedCleanupCount = 0;
        cleanupTail = -1;
        freeCleanupNode = -1;
        nextCleanupNode = 0;
        cursor = 0;
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
        closed = true;
    }

    private int trackedNode(long handle) {
        int slot = validSlot(handle);
        if (slot < 0) return -1;
        int node = trackedNodeBySlot[slot];
        return node >= 0 && ownedCleanup[node] == handle ? node : -1;
    }

    private boolean trackOwner(long handle) {
        int slot = validSlot(handle);
        if (slot < 0 || trackedNodeBySlot[slot] >= 0
                || ownedCleanupCount >= ownedCleanup.length
                || lastCleanupSerial == Long.MAX_VALUE) return false;

        int node;
        if (freeCleanupNode >= 0) {
            node = freeCleanupNode;
            freeCleanupNode = cleanupNext[node];
        } else {
            if (nextCleanupNode >= ownedCleanup.length) return false;
            node = nextCleanupNode++;
        }
        ownedCleanup[node] = handle;
        ownedCleanupSerial[node] = ++lastCleanupSerial;
        cleanupPrev[node] = cleanupTail;
        cleanupNext[node] = -1;
        if (cleanupTail >= 0) cleanupNext[cleanupTail] = node;
        cleanupTail = node;
        trackedNodeBySlot[slot] = node;
        ownedCleanupCount++;
        return true;
    }

    private void unlinkOwner(int node) {
        int prev = cleanupPrev[node], next = cleanupNext[node];
        if (prev >= 0) cleanupNext[prev] = next;
        if (next >= 0) cleanupPrev[next] = prev;
        else cleanupTail = prev;
        long handle = ownedCleanup[node];
        int slot = (int) (handle & SLOT_MASK) - 1;
        if (slot >= 0 && slot < trackedNodeBySlot.length && trackedNodeBySlot[slot] == node)
            trackedNodeBySlot[slot] = -1;
        ownedCleanup[node] = INVALID_HANDLE;
        ownedCleanupSerial[node] = 0;
        cleanupPrev[node] = -1;
        cleanupNext[node] = freeCleanupNode;
        freeCleanupNode = node;
        ownedCleanupCount--;
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

    private long generationOf(long token) {
        return token == 0L ? 0L : (token >>> SLOT_BITS) ^ handleSalt;
    }
}
