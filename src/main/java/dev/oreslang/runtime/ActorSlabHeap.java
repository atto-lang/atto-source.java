package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, size-class slab allocator for one actor incarnation: the Java twin
 * of atto-source.llvm {@code runtime/ActorSlabHeap.hpp}, atto-source.rs
 * {@code src/actor_slab_heap.rs} and atto-source.net {@code ActorSlabHeap.cs}.
 *
 * <p>Actor object bytes live in one off-heap direct buffer, so objects add no
 * per-object Java allocations. Page metadata is a handful of primitive arrays
 * sized by page count, not object count. Guest references are 4-byte
 * {@code int} words on every JVM, independent of compressed oops:
 * <ul>
 *   <li><b>Offset32</b> = {@code offset / 8 + 1} (the cross-backend word): no
 *   per-object metadata, for compiler-proven actor-local lifetimes. Rejects
 *   null, foreign, interior, unallocated and freed words, not ABA.</li>
 *   <li><b>Handle32</b> = {@code (generation << slotBits) | (slot + 1)} through
 *   an 8-byte table entry: capacity-sized slot bits, FIFO reuse, exhausted
 *   slots quarantined, never wrapped.</li>
 * </ul>
 * Allocation decisions mirror the other backends exactly, so
 * {@link #goldenScriptV1()} must reproduce the shared fixture
 * {@code contracts/actor-slab-heap/v1/actor_slab_heap_golden_v1.txt}. Words are
 * unsigned 32-bit values carried in {@code int}. Memory is committed in
 * granules up to a budget; growth copies into a larger buffer, and references
 * are offsets, so they stay valid. Every failure returns a status and leaves
 * the heap unchanged. Single owner: the actor turn. Not thread-safe. A direct
 * buffer limits one actor heap to 2 GiB.
 */
public final class ActorSlabHeap implements AutoCloseable {
    public static final int PAGE_BYTES = 4096;
    public static final int HEADER_BYTES = 64;
    public static final int MAX_SMALL = 2048;
    public static final int MAX_ALIGNMENT = 64;
    private static final int[] CLASS_BYTES = {
        16, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512,
        640, 768, 896, 1024, 1280, 1536, 1792, 2048,
    };
    private static final int CLASSES = CLASS_BYTES.length;
    private static final int BITMAP_BITS = (HEADER_BYTES - 16) * 8;
    private static final int NONE = 0;
    private static final long MAX_SPAN = Integer.MAX_VALUE & ~(long) (PAGE_BYTES - 1); // direct buffer limit

    public enum Status { OK, OUT_OF_MEMORY, BUDGET_EXCEEDED, INVALID_SIZE, INVALID_ALIGNMENT, INVALID_REF,
        STALE_HANDLE, HANDLE_TABLE_FULL, INVALID_CONFIG, RETIRED }

    /** Result word for allocate/resolve: valid only when {@code status == OK}. */
    public record Result(Status status, int word) {
        public boolean ok() { return status == Status.OK; }
    }

    private static final byte UNUSED = 0, SMALL = 1, LARGE = 2, LARGE_TAIL = 3, FREE = 4, FREE_TAIL = 5, META = 6;

    private ByteBuffer mem = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN);
    private int committed;
    private final int reserve, budget, granule, maxPages;
    private int pageSlots, highWater, freeRuns;
    // Page metadata as parallel primitive arrays (per page, never per object).
    private byte[] kind = new byte[0], cls = new byte[0];
    private short[] live = new short[0], freeHead = new short[0], bump = new short[0];
    private int[] prev = new int[0], next = new int[0], run = new int[0];
    private long[] bitmap = new long[0]; // BITMAP_BITS / 64 words per page
    private final int[] partial = new int[CLASSES];
    private int handleBase, handleCapacity, slotBits, maxGen, freeSlotHead, freeSlotTail, quarantined;
    private boolean retired;
    private long liveObjects, liveBlockBytes, livePages, freePages, metadataBytes;

    public ActorSlabHeap(long reserveBytes, long budgetBytes) { this(reserveBytes, budgetBytes, 64 * 1024, 0, 0); }

    public ActorSlabHeap(long reserveBytes, long budgetBytes, int commitGranule, int handleCapacity, int handleGenerationBits) {
        long reserveL = reserveBytes / PAGE_BYTES * PAGE_BYTES;
        if (reserveL == 0 || budgetBytes <= 0 || budgetBytes > reserveL || reserveL > MAX_SPAN)
            throw new IllegalArgumentException("invalid actor slab heap configuration");
        reserve = (int) reserveL;
        budget = (int) budgetBytes;
        granule = Math.max(commitGranule, PAGE_BYTES) / PAGE_BYTES * PAGE_BYTES;
        maxPages = reserve / PAGE_BYTES;
        metadataBytes = maxPages;
        if (handleCapacity != 0) {
            Status s = initHandles(handleCapacity, handleGenerationBits);
            if (s != Status.OK) throw new IllegalArgumentException("invalid Handle32 profile: " + s);
        }
    }

    private Status initHandles(int capacity, int genBitsCfg) {
        if (capacity < 0) return Status.INVALID_CONFIG;
        int bits = 0;
        while ((1L << bits) <= Integer.toUnsignedLong(capacity)) bits++;
        if (bits >= 31) return Status.INVALID_CONFIG;
        int genBits = 32 - bits;
        if (genBitsCfg != 0) {
            if (genBitsCfg < 2 || genBitsCfg > genBits) return Status.INVALID_CONFIG;
            genBits = genBitsCfg;
        }
        int count = (int) (((long) capacity * 8 + HEADER_BYTES + PAGE_BYTES - 1) / PAGE_BYTES);
        long taken = takePages(count);
        if (taken < 0) return Status.values()[(int) -taken];
        int first = (int) taken;
        resetPage(first);
        kind[first] = META;
        run[first] = count;
        for (int p = 1; p < count; p++) kind[first + p] = LARGE_TAIL;
        handleBase = first * PAGE_BYTES + HEADER_BYTES;
        handleCapacity = capacity;
        slotBits = bits;
        maxGen = (int) ((1L << genBits) - 1);
        for (int i = 0; i < capacity; i++) writeEntry(i, i + 1 < capacity ? i + 2 : NONE, 1);
        freeSlotHead = 1;
        freeSlotTail = capacity;
        metadataBytes += (long) count * PAGE_BYTES;
        livePages += count;
        return Status.OK;
    }

    public long liveObjects() { return liveObjects; }
    public long livePages() { return livePages; }
    public long freePages() { return freePages; }
    public long committedBytes() { return committed; }
    public long liveBlockBytes() { return liveBlockBytes; }
    public long metadataBytes() { return metadataBytes; }
    public int quarantinedHandles() { return quarantined; }
    public int handleSlotBits() { return slotBits; }
    public int handleMaxGeneration() { return maxGen; }

    // ---- memory -------------------------------------------------------------
    private Status commitThrough(long end) {
        if (end <= committed) return Status.OK;
        if (end > reserve) return Status.OUT_OF_MEMORY;
        long target = Math.min((end + granule - 1) / granule * granule, reserve);
        if (target > budget) {
            if (end > budget) return Status.BUDGET_EXCEEDED;
            target = budget;
        }
        ByteBuffer grown;
        try {
            grown = ByteBuffer.allocateDirect((int) target).order(ByteOrder.LITTLE_ENDIAN);
        } catch (OutOfMemoryError e) {
            return Status.OUT_OF_MEMORY; // old buffer untouched
        }
        grown.put(0, mem, 0, committed);
        mem = grown;
        committed = (int) target;
        return Status.OK;
    }

    private void ensurePageSlots(int needed) {
        if (needed <= pageSlots) return;
        int slots = Math.min(Math.max(needed, pageSlots * 2), maxPages);
        kind = java.util.Arrays.copyOf(kind, slots);
        cls = java.util.Arrays.copyOf(cls, slots);
        live = java.util.Arrays.copyOf(live, slots);
        freeHead = java.util.Arrays.copyOf(freeHead, slots);
        bump = java.util.Arrays.copyOf(bump, slots);
        prev = java.util.Arrays.copyOf(prev, slots);
        next = java.util.Arrays.copyOf(next, slots);
        run = java.util.Arrays.copyOf(run, slots);
        bitmap = java.util.Arrays.copyOf(bitmap, slots * (BITMAP_BITS / 64));
        pageSlots = slots;
    }

    private void resetPage(int p) {
        kind[p] = UNUSED; cls[p] = 0; live[p] = 0; freeHead[p] = 0; bump[p] = 0;
        prev[p] = NONE; next[p] = NONE; run[p] = 0;
        java.util.Arrays.fill(bitmap, p * (BITMAP_BITS / 64), (p + 1) * (BITMAP_BITS / 64), 0L);
    }

    /** First page index, or a negative {@code -Status.ordinal()} on failure. */
    private long takePages(int count) {
        for (int link = freeRuns; link != NONE; link = next[link - 1]) {
            int head = link - 1;
            int have = run[head];
            if (have < count) continue;
            unlinkFreeRun(head);
            if (have > count) pushFreeRun(head + count, have - count);
            freePages -= count;
            return head;
        }
        if (count > maxPages - highWater) return -Status.OUT_OF_MEMORY.ordinal();
        Status s = commitThrough((long) (highWater + count) * PAGE_BYTES);
        if (s != Status.OK) return -s.ordinal();
        ensurePageSlots(highWater + count);
        int first = highWater;
        highWater += count;
        return first;
    }

    private void pushFreeRun(int first, int count) {
        int after = first + count;
        if (after < highWater && kind[after] == FREE) {
            int more = run[after];
            unlinkFreeRun(after);
            count += more;
        }
        resetPage(first);
        kind[first] = FREE;
        run[first] = count;
        next[first] = freeRuns;
        for (int p = 1; p < count; p++) kind[first + p] = FREE_TAIL;
        if (freeRuns != NONE) prev[freeRuns - 1] = first + 1;
        freeRuns = first + 1;
    }

    private void unlinkFreeRun(int first) {
        int pv = prev[first], nx = next[first];
        if (pv != NONE) next[pv - 1] = nx; else freeRuns = nx;
        if (nx != NONE) prev[nx - 1] = pv;
        kind[first] = UNUSED;
    }

    private void partialPush(int c, int index) {
        int head = partial[c];
        prev[index] = NONE;
        next[index] = head;
        if (head != NONE) prev[head - 1] = index + 1;
        partial[c] = index + 1;
    }

    private void partialRemove(int c, int index) {
        int pv = prev[index], nx = next[index];
        if (pv != NONE) next[pv - 1] = nx; else partial[c] = nx;
        if (nx != NONE) prev[nx - 1] = pv;
        prev[index] = NONE;
        next[index] = NONE;
    }

    private boolean bit(int p, int i) { return ((bitmap[p * (BITMAP_BITS / 64) + (i >>> 6)] >>> (i & 63)) & 1L) == 1L; }
    private void setBit(int p, int i, boolean on) {
        int w = p * (BITMAP_BITS / 64) + (i >>> 6);
        if (on) bitmap[w] |= 1L << (i & 63); else bitmap[w] &= ~(1L << (i & 63));
    }
    private static int blocksInPage(int size) { return Math.min((PAGE_BYTES - HEADER_BYTES) / size, BITMAP_BITS); }
    private static int classFor(long bytes, long align) {
        for (int c = 0; c < CLASSES; c++) if (CLASS_BYTES[c] >= bytes && CLASS_BYTES[c] % align == 0) return c;
        return -1;
    }
    private void zero(int at, int len) {
        for (int i = 0; i < len; i++) mem.put(at + i, (byte) 0);
    }
    private static int encode(int offset) { return offset / 8 + 1; } // offset < 2 GiB: always fits

    // ---- Offset32 -----------------------------------------------------------
    public Result allocate(long bytes, long align) {
        if (retired) return new Result(Status.RETIRED, 0);
        if (bytes <= 0) return new Result(Status.INVALID_SIZE, 0);
        if (align <= 0 || (align & (align - 1)) != 0 || align > MAX_ALIGNMENT) return new Result(Status.INVALID_ALIGNMENT, 0);
        align = Math.max(align, 8);
        int c = bytes <= MAX_SMALL ? classFor(bytes, align) : -1;
        if (c < 0) return allocateLarge(bytes);
        int index;
        if (partial[c] != NONE) {
            index = partial[c] - 1;
        } else {
            long taken = takePages(1);
            if (taken < 0) return new Result(Status.values()[(int) -taken], 0);
            index = (int) taken;
            resetPage(index);
            kind[index] = SMALL;
            cls[index] = (byte) c;
            partialPush(c, index);
            metadataBytes += HEADER_BYTES;
            livePages += 1;
        }
        int size = CLASS_BYTES[c];
        int pageBase = index * PAGE_BYTES + HEADER_BYTES;
        int block;
        if (freeHead[index] != 0) {
            block = Short.toUnsignedInt(freeHead[index]) - 1;
            freeHead[index] = mem.getShort(pageBase + block * size);
        } else {
            block = Short.toUnsignedInt(bump[index]);
            bump[index] = (short) (block + 1);
        }
        setBit(index, block, true);
        live[index] = (short) (Short.toUnsignedInt(live[index]) + 1);
        if (freeHead[index] == 0 && Short.toUnsignedInt(bump[index]) == blocksInPage(size)) partialRemove(c, index);
        int offset = pageBase + block * size;
        zero(offset, size);
        liveBlockBytes += size;
        liveObjects += 1;
        return new Result(Status.OK, encode(offset));
    }

    private Result allocateLarge(long bytes) {
        if (bytes > reserve) return new Result(Status.OUT_OF_MEMORY, 0);
        int count = (int) ((bytes + HEADER_BYTES + PAGE_BYTES - 1) / PAGE_BYTES);
        long taken = takePages(count);
        if (taken < 0) return new Result(Status.values()[(int) -taken], 0);
        int first = (int) taken;
        resetPage(first);
        kind[first] = LARGE;
        run[first] = count;
        for (int p = 1; p < count; p++) kind[first + p] = LARGE_TAIL;
        int payload = count * PAGE_BYTES - HEADER_BYTES;
        int offset = first * PAGE_BYTES + HEADER_BYTES;
        zero(offset, payload);
        metadataBytes += HEADER_BYTES;
        livePages += count;
        liveBlockBytes += payload;
        liveObjects += 1;
        return new Result(Status.OK, encode(offset));
    }

    /** {page, block-or-run, usable, large?1:0} or null with {@code lastStatus} set. */
    private Status lastStatus;
    private int[] locate(int word) {
        if (retired) { lastStatus = Status.RETIRED; return null; }
        lastStatus = Status.INVALID_REF;
        if (word == 0) return null;
        long offset = (Integer.toUnsignedLong(word) - 1) * 8;
        if (offset >= reserve) return null;
        int index = (int) (offset / PAGE_BYTES);
        int within = (int) (offset % PAGE_BYTES);
        if (index >= highWater || within < HEADER_BYTES) return null;
        if (kind[index] == SMALL) {
            int size = CLASS_BYTES[cls[index]];
            if ((within - HEADER_BYTES) % size != 0) return null;
            int block = (within - HEADER_BYTES) / size;
            if (block >= Short.toUnsignedInt(bump[index]) || !bit(index, block)) return null;
            lastStatus = Status.OK;
            return new int[] {index, block, size, 0};
        }
        if (kind[index] == LARGE && within == HEADER_BYTES) { // META is never an object
            lastStatus = Status.OK;
            return new int[] {index, run[index], run[index] * PAGE_BYTES - HEADER_BYTES, 1};
        }
        return null;
    }

    public Status free(int word) {
        int[] at = locate(word);
        if (at == null) return lastStatus;
        int index = at[0], block = at[1], usable = at[2];
        if (at[3] == 1) {
            zero(index * PAGE_BYTES + HEADER_BYTES, usable);
            pushFreeRun(index, block);
            freePages += block;
            livePages -= block;
            metadataBytes -= HEADER_BYTES;
            liveBlockBytes -= usable;
            liveObjects -= 1;
            return Status.OK;
        }
        int size = usable;
        int addr = index * PAGE_BYTES + HEADER_BYTES + block * size;
        zero(addr, size);
        boolean wasFull = freeHead[index] == 0 && Short.toUnsignedInt(bump[index]) == blocksInPage(size);
        setBit(index, block, false);
        live[index] = (short) (Short.toUnsignedInt(live[index]) - 1);
        liveBlockBytes -= size;
        liveObjects -= 1;
        if (live[index] == 0) {
            if (!wasFull) partialRemove(cls[index], index);
            pushFreeRun(index, 1);
            freePages += 1;
            livePages -= 1;
            metadataBytes -= HEADER_BYTES;
            return Status.OK;
        }
        mem.putShort(addr, freeHead[index]);
        freeHead[index] = (short) (block + 1);
        if (wasFull) partialPush(cls[index], index);
        return Status.OK;
    }

    /** Checked copy-out; {@code length} must fit the live block. */
    public Status read(int word, byte[] into, int length) {
        int[] at = locate(word);
        if (at == null) return lastStatus;
        if (length < 0 || length > at[2] || length > into.length) return Status.INVALID_SIZE;
        mem.get((int) ((Integer.toUnsignedLong(word) - 1) * 8), into, 0, length);
        return Status.OK;
    }

    /** Checked copy-in; {@code length} must fit the live block. */
    public Status write(int word, byte[] from, int length) {
        int[] at = locate(word);
        if (at == null) return lastStatus;
        if (length < 0 || length > at[2] || length > from.length) return Status.INVALID_SIZE;
        mem.put((int) ((Integer.toUnsignedLong(word) - 1) * 8), from, 0, length);
        return Status.OK;
    }

    // ---- Handle32 -----------------------------------------------------------
    private int entryWord(int slot) { return mem.getInt(handleBase + slot * 8); }
    private int entryGen(int slot) { return mem.getInt(handleBase + slot * 8 + 4); }
    private void writeEntry(int slot, int word, int gen) {
        mem.putInt(handleBase + slot * 8, word);
        mem.putInt(handleBase + slot * 8 + 4, gen);
    }

    public Result allocateHandle(long bytes, long align) {
        if (retired) return new Result(Status.RETIRED, 0);
        if (handleCapacity == 0 || freeSlotHead == NONE) return new Result(Status.HANDLE_TABLE_FULL, 0);
        Result obj = allocate(bytes, align);
        if (!obj.ok()) return obj;
        int slot = freeSlotHead - 1;
        int nx = entryWord(slot), gen = entryGen(slot);
        freeSlotHead = nx;
        if (nx == NONE) freeSlotTail = NONE;
        writeEntry(slot, obj.word(), gen);
        return new Result(Status.OK, (gen << slotBits) | (slot + 1));
    }

    public Result resolveHandle(int handle) {
        if (retired) return new Result(Status.RETIRED, 0);
        int slot1 = handle & ((1 << slotBits) - 1);
        if (handleCapacity == 0 || slot1 == 0 || slot1 > handleCapacity) return new Result(Status.INVALID_REF, 0);
        int w = entryWord(slot1 - 1), gen = entryGen(slot1 - 1);
        if (gen == 0 || (handle >>> slotBits) != gen || w == 0) return new Result(Status.STALE_HANDLE, 0);
        return new Result(Status.OK, w);
    }

    public Status freeHandle(int handle) {
        Result r = resolveHandle(handle);
        if (!r.ok()) return r.status();
        Status s = free(r.word());
        if (s != Status.OK) return s;
        int slot = (handle & ((1 << slotBits) - 1)) - 1;
        int gen = entryGen(slot);
        if (gen == maxGen) {
            writeEntry(slot, NONE, 0); // quarantine: never wrap, never reuse
            quarantined++;
            return Status.OK;
        }
        writeEntry(slot, NONE, gen + 1);
        if (freeSlotTail != NONE) writeEntry(freeSlotTail - 1, slot + 1, entryGen(freeSlotTail - 1));
        else freeSlotHead = slot + 1;
        freeSlotTail = slot + 1;
        return Status.OK;
    }

    /** Actor STOP: scrub and drop the buffer. Idempotent. */
    @Override
    public void close() {
        if (!retired) zero(0, committed);
        mem = ByteBuffer.allocateDirect(0);
        committed = 0;
        retired = true;
        liveObjects = liveBlockBytes = livePages = freePages = metadataBytes = 0;
    }

    /**
     * Cross-backend conformance script. Words per op: allocate → ref32 word;
     * free → 0; allocateHandle → handle word then its resolved word;
     * freeHandle → 1. Words are unsigned (compare with Integer.toUnsignedLong).
     */
    public static List<Integer> goldenScriptV1() {
        List<Integer> out = new ArrayList<>();
        try (ActorSlabHeap h = new ActorSlabHeap(16L << 20, 16L << 20, 64 * 1024, 64, 0)) {
            List<Integer> liveRefs = new ArrayList<>(), handles = new ArrayList<>();
            int[] lcg = {7};
            java.util.function.IntSupplier nextR = () -> { lcg[0] = lcg[0] * 1664525 + 1013904223; return lcg[0] >>> 8; };
            long[] sizes = {8, 16, 24, 40, 64, 100, 200, 512, 1000, 2048, 3000, 9000};
            long[] aligns = {8, 16, 32, 64};
            for (int op = 0; op < 2000; op++) {
                int v = Integer.remainderUnsigned(nextR.getAsInt(), 10);
                if (v <= 4) {
                    long size = sizes[Integer.remainderUnsigned(nextR.getAsInt(), 12)];
                    long align = aligns[Integer.remainderUnsigned(nextR.getAsInt(), 4)];
                    Result r = h.allocate(size, align);
                    if (!r.ok()) throw new IllegalStateException("golden allocate " + r.status());
                    out.add(r.word());
                    liveRefs.add(r.word());
                } else if ((v == 5 || v == 6) && !liveRefs.isEmpty()) {
                    int i = Integer.remainderUnsigned(nextR.getAsInt(), liveRefs.size());
                    if (h.free(liveRefs.get(i)) != Status.OK) throw new IllegalStateException("golden free");
                    liveRefs.set(i, liveRefs.get(liveRefs.size() - 1));
                    liveRefs.remove(liveRefs.size() - 1);
                    out.add(0);
                } else if (v == 7) {
                    Result x = h.allocateHandle(sizes[Integer.remainderUnsigned(nextR.getAsInt(), 6)], 8);
                    if (x.ok()) {
                        out.add(x.word());
                        out.add(h.resolveHandle(x.word()).word());
                        handles.add(x.word());
                    }
                } else if (v == 8 && !handles.isEmpty()) {
                    int i = Integer.remainderUnsigned(nextR.getAsInt(), handles.size());
                    if (h.freeHandle(handles.get(i)) != Status.OK) throw new IllegalStateException("golden freeHandle");
                    handles.set(i, handles.get(handles.size() - 1));
                    handles.remove(handles.size() - 1);
                    out.add(1);
                }
            }
        }
        return out;
    }

    /** Canonical status codes shared by every backend (golden v2). */
    public static int code(Status s) {
        return switch (s) {
            case OK -> 0;
            case OUT_OF_MEMORY -> 1;
            case BUDGET_EXCEEDED -> 2;
            case INVALID_SIZE -> 3;
            case INVALID_ALIGNMENT -> 4;
            case INVALID_REF -> 5;
            case STALE_HANDLE -> 6;
            case HANDLE_TABLE_FULL -> 7;
            case RETIRED -> 8;
            case INVALID_CONFIG -> 99;
        };
    }

    private static int fail(Status s) { return 0x8000_0000 | code(s); }

    private static void remember(List<Integer> v, int w) {
        v.add(w);
        if (v.size() > 8) v.remove(0);
    }

    private static void swapRemove(List<Integer> v, int i) {
        v.set(i, v.get(v.size() - 1));
        v.remove(v.size() - 1);
    }

    /**
     * Golden v2: failure paths and edge cases; same script as the C++
     * {@code tests/actor_slab_heap_golden_v2_test.cpp} (see it for the format).
     * Words are unsigned 32-bit values carried in {@code int}.
     */
    public static List<Integer> goldenScriptV2() {
        List<Integer> out = new ArrayList<>();
        try (ActorSlabHeap h = new ActorSlabHeap(256 * 1024, 128 * 1024, 16 * 1024, 8, 3)) {
            int[] lcg = {99};
            java.util.function.IntSupplier nextR = () -> { lcg[0] = lcg[0] * 1664525 + 1013904223; return lcg[0] >>> 8; };
            long[] sizes = {0, 1, 8, 16, 17, 33, 48, 63, 64, 65, 100, 127, 128, 129, 255, 256, 257, 500, 1000, 2047, 2048, 2049};
            long[] aligns = {1, 8, 16, 32, 64, 128, 3};
            long[] larges = {3000, 4033, 4032, 8000, 12000, 20000};
            List<Integer> live = new ArrayList<>(), freed = new ArrayList<>(),
                handles = new ArrayList<>(), freedHandles = new ArrayList<>();
            for (int op = 0; op < 3000; op++) {
                int v = Integer.remainderUnsigned(nextR.getAsInt(), 16);
                if (v <= 5) {
                    long s = sizes[Integer.remainderUnsigned(nextR.getAsInt(), 22)];
                    long a = aligns[Integer.remainderUnsigned(nextR.getAsInt(), 7)];
                    Result r = h.allocate(s, a);
                    if (r.ok()) { out.add(r.word()); live.add(r.word()); } else out.add(fail(r.status()));
                } else if (v <= 8) {
                    if (live.isEmpty()) continue;
                    int i = Integer.remainderUnsigned(nextR.getAsInt(), live.size());
                    int w = live.get(i);
                    Status st = h.free(w);
                    out.add(st == Status.OK ? 0 : fail(st));
                    swapRemove(live, i);
                    remember(freed, w);
                } else if (v == 9) {
                    if (freed.isEmpty()) continue;
                    int w = freed.get(Integer.remainderUnsigned(nextR.getAsInt(), freed.size()));
                    Status st = h.free(w);
                    out.add(st == Status.OK ? 0 : fail(st));
                    if (st == Status.OK) { int i = live.indexOf(w); if (i >= 0) swapRemove(live, i); }
                } else if (v <= 11) {
                    Result x = h.allocateHandle(sizes[1 + Integer.remainderUnsigned(nextR.getAsInt(), 12)], 8);
                    if (x.ok()) { out.add(x.word()); handles.add(x.word()); } else out.add(fail(x.status()));
                } else if (v <= 13) {
                    if (handles.isEmpty()) continue;
                    int i = Integer.remainderUnsigned(nextR.getAsInt(), handles.size());
                    int w = handles.get(i);
                    Status st = h.freeHandle(w);
                    out.add(st == Status.OK ? 0 : fail(st));
                    swapRemove(handles, i);
                    remember(freedHandles, w);
                } else if (v == 14) {
                    if (freedHandles.isEmpty()) continue;
                    Result r = h.resolveHandle(freedHandles.get(Integer.remainderUnsigned(nextR.getAsInt(), freedHandles.size())));
                    out.add(r.ok() ? r.word() : fail(r.status()));
                } else {
                    Result r = h.allocate(larges[Integer.remainderUnsigned(nextR.getAsInt(), 6)], 8);
                    if (r.ok()) { out.add(r.word()); live.add(r.word()); } else out.add(fail(r.status()));
                }
            }
            out.add((int) h.liveObjects());
            out.add((int) h.livePages());
            out.add((int) h.freePages());
            out.add(h.quarantinedHandles());
            out.add((int) h.committedBytes());
        }
        return out;
    }
}
