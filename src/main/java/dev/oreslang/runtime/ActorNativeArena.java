package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Explicitly freed, actor-owned native storage for source object fields.
 * Native bytes contain tagged values and actor-relative logical offsets, never
 * Java object pointers or raw host addresses. Java facades and layout metadata remain managed;
 * this allocator does not claim process-wide zero GC. All accesses, including
 * close, are serialized so a region cannot be freed during an access.
 */
public final class ActorNativeArena implements AutoCloseable {
    /** Only facades whose guest payload is in this arena may occupy reference slots. */
    public interface NativeValue {
        ActorNativeArena nativeArena();

        /**
         * Stable actor-relative logical address of this native payload.
         * This is never a host pointer and is never recycled while the actor lives.
         */
        default long nativeOffset() { return -1L; }
    }

    @FunctionalInterface
    interface Budget {
        Runnable reserve(int bytes);
    }

    private static final int SLOT_BYTES = 24;
    private static final int TASK_FRAME_BYTES = 32;
    private static final long TASK_FRAME_MAGIC = 0x4f5245535441534bL; // "ORESTASK"
    private static final int SLAB_BYTES = 4096;
    private static final long NULL = 0, UNINITIALIZED = 1, BOOLEAN = 2,
            BYTE = 3, SHORT = 4, INTEGER = 5, LONG = 6, FLOAT = 7, DOUBLE = 8,
            CHARACTER = 9, STRING = 10, REFERENCE = 11, BIG_INTEGER = 12,
            BIG_DECIMAL = 13, PROCESS_SYMBOL = 14, STABLE_SYMBOL = 15;

    private final Runnable checkOwner;
    private final Budget budget;
    private final long limit;
    private final ArrayList<ByteBuffer> slabs = new ArrayList<>();
    private final ArrayList<Long> slabBases = new ArrayList<>();
    private final ArrayList<Runnable> releases = new ArrayList<>();
    private final IdentityHashMap<NativeValue, Long> referenceOffsets = new IdentityHashMap<>();
    private final Map<Long, NativeValue> referencesByOffset = new HashMap<>();
    private final Set<Region> regions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Monotonic actor-relative address allocator; retired addresses are never reused. */
    private long nextActorOffset;
    private int tail;
    private long committed;
    private long allocated;
    private boolean closed;
    /** Lazy per-actor byte-addressed guest heap; no other actor can access it. */
    private ActorLinearMemory linearMemory;

    ActorNativeArena(long limit, Runnable checkOwner, Budget budget) {
        if (limit <= 0) throw new IllegalArgumentException("native arena limit must be positive");
        this.limit = limit;
        this.checkOwner = Objects.requireNonNull(checkOwner);
        this.budget = Objects.requireNonNull(budget);
    }

    /** Fixed field names are layout metadata; values are stored only in native slots. */
    public synchronized Map<String, Object> fields(List<String> names, Object uninitialized) {
        checkAccess();
        List<String> layout = List.copyOf(names);
        if (Set.copyOf(layout).size() != layout.size()) {
            throw new IllegalArgumentException("duplicate native object field");
        }
        long slots = allocate(Math.multiplyExact(layout.size(), SLOT_BYTES));
        return new Fields(layout, slots, uninitialized);
    }

    public synchronized long committedBytes() { return committed; }
    public synchronized long allocatedBytes() { return allocated; }
    public synchronized boolean closed() { return closed; }

    synchronized void assertOwner() { checkAccess(); }

    /**
     * Exactly one growable linear guest address space per actor arena.
     * Pages are physically private native allocations; guest offsets are
     * logical addresses, never raw host pointers or JVM heap references.
     * Memory is provisioned lazily so idle actors need no 64-KiB reservation.
     */
    public synchronized ActorLinearMemory linearMemory(int initialPages, int maxPages) {
        checkAccess();
        if (initialPages < 0 || maxPages < 0 || maxPages > ActorLinearMemory.MAX_PAGES
                || initialPages > maxPages) {
            throw new IllegalArgumentException("invalid actor linear memory initial/maximum pages");
        }
        if (linearMemory == null) {
            ActorLinearMemory created = new ActorLinearMemory(this, maxPages);
            created.grow(initialPages);
            linearMemory = created;
        } else {
            if (linearMemory.maxPages() != maxPages || linearMemory.pages() < initialPages) {
                throw new IllegalArgumentException("actor linear memory configuration mismatch");
            }
        }
        return linearMemory;
    }

    /**
     * Compact actor-owned continuation authority. The Java SourceTask may remain
     * an execution facade during lowering, but resume/suspend/terminal authority
     * is persisted here in native actor memory and dies with the arena.
     */
    public synchronized TaskFrame taskFrame() {
        checkAccess();
        long base = allocate(TASK_FRAME_BYTES);
        write(base, 0, TASK_FRAME_MAGIC);
        write(base, 8, TaskPhase.NEW.code);
        write(base, 16, 0L); // resume generation/count
        write(base, 24, 0L); // reserved for lowered PC/flags
        return new TaskFrame(base);
    }

    public enum TaskPhase {
        NEW(0), RUNNING(1), AWAITING(2), COOPERATING(3), DONE(4), FAILED(5);

        private final long code;
        TaskPhase(long code) { this.code = code; }

        private static TaskPhase fromCode(long code) {
            for (TaskPhase phase : values()) if (phase.code == code) return phase;
            throw new IllegalStateException("invalid native actor task-frame phase " + code);
        }
    }

    public final class TaskFrame implements NativeValue {
        private final long base;

        private TaskFrame(long base) {
            this.base = base;
        }

        @Override public ActorNativeArena nativeArena() { return ActorNativeArena.this; }

        @Override public long nativeOffset() {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                return base;
            }
        }

        public TaskPhase phase() {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                return TaskPhase.fromCode(read(base, 8));
            }
        }

        public long resumeCount() {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                return read(base, 16);
            }
        }

        public void beginResume(boolean initial) {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (initial ? current != TaskPhase.NEW
                        : current != TaskPhase.AWAITING && current != TaskPhase.COOPERATING) {
                    throw new IllegalStateException(
                            "illegal native actor task resume: initial=" + initial
                                    + " phase=" + current);
                }
                long generation = Math.addExact(read(base, 16), 1L);
                write(base, 16, generation);
                write(base, 8, TaskPhase.RUNNING.code);
            }
        }

        public void awaiting() { transitionFromRunning(TaskPhase.AWAITING); }
        public void cooperating() { transitionFromRunning(TaskPhase.COOPERATING); }
        public void done() { transitionFromRunning(TaskPhase.DONE); }

        /**
         * Failure is terminal from any live phase. This is deliberately more
         * permissive than normal transitions so an exception raised after a
         * suspension decision cannot mask the original failure with a phase error.
         */
        public void failed() {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (current == TaskPhase.DONE || current == TaskPhase.FAILED) return;
                write(base, 8, TaskPhase.FAILED.code);
            }
        }

        private void transitionFromRunning(TaskPhase next) {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (current != TaskPhase.RUNNING) {
                    throw new IllegalStateException(
                            "native actor task transition requires RUNNING; phase="
                                    + current + " next=" + next);
                }
                write(base, 8, next.code);
            }
        }

        private void checkFrame() {
            checkAccess();
            if (read(base, 0) != TASK_FRAME_MAGIC) {
                throw new SecurityException("invalid or stale native actor task frame");
            }
        }
    }

    /** Primitive access path for dense stores: no per-element Java objects or codecs. */
    public synchronized Region region(int bytes) {
        checkAccess();
        if (bytes <= 0 || bytes > limit - committed) {
            throw new IllegalArgumentException("native region exceeds actor arena budget");
        }
        Runnable release = budget.reserve(bytes);
        ByteBuffer memory = null;
        try {
            NativeCarrierExecutor.ensureNativeLibraryLoaded();
            memory = allocateNative(bytes);
            if (memory == null) throw new OutOfMemoryError("native actor region allocation failed");
            long baseOffset = reserveActorOffset(bytes, Long.BYTES);
            Region region = new Region(memory.order(ByteOrder.LITTLE_ENDIAN), bytes, baseOffset, release);
            regions.add(region);
            committed += bytes;
            allocated += bytes;
            return region;
        } catch (RuntimeException | Error failure) {
            if (memory != null) releaseNative(memory);
            release.run();
            throw failure;
        }
    }

    public final class Region implements AutoCloseable, NativeValue {
        private ByteBuffer memory;
        private final int capacity;
        private final long baseOffset;
        private final Runnable release;
        private int readLeases;
        private Region(ByteBuffer memory, int capacity, long baseOffset, Runnable release) {
            this.memory = memory;
            this.capacity = capacity;
            this.baseOffset = baseOffset;
            this.release = release;
        }
        @Override public ActorNativeArena nativeArena() { return ActorNativeArena.this; }
        @Override public long nativeOffset() {
            synchronized (ActorNativeArena.this) {
                checked();
                return baseOffset;
            }
        }
        public int capacity() { return capacity; }
        public boolean closed() {
            synchronized (ActorNativeArena.this) { return memory == null; }
        }
        private ByteBuffer checked() {
            checkAccess();
            if (memory == null) throw new IllegalStateException("actor native region is closed");
            return memory;
        }
        public byte readByte(int offset) {
            synchronized (ActorNativeArena.this) { return checked().get(offset); }
        }
        public void writeByte(int offset, byte value) {
            synchronized (ActorNativeArena.this) { requireWritable().put(offset, value); }
        }
        void assertWritable() {
            synchronized (ActorNativeArena.this) { requireWritable(); }
        }
        public long readLong(int offset) {
            synchronized (ActorNativeArena.this) { return checked().getLong(offset); }
        }
        public void writeLong(int offset, long value) {
            synchronized (ActorNativeArena.this) { requireWritable().putLong(offset, value); }
        }
        public double readDouble(int offset) {
            synchronized (ActorNativeArena.this) { return checked().getDouble(offset); }
        }
        public void writeDouble(int offset, double value) {
            synchronized (ActorNativeArena.this) { requireWritable().putDouble(offset, value); }
        }

        private ByteBuffer requireWritable() {
            ByteBuffer bytes = checked();
            if (readLeases != 0) throw new IllegalStateException("native region has active read-only leases");
            return bytes;
        }

        /**
         * Temporarily freezes writes to the entire region. The returned slice
         * does not expose a raw address or bypass the actor's ownership check.
         * The lease must be closed before the region can be explicitly released.
         */
        public ReadOnlyLease leaseReadOnly(int offset, int length) {
            synchronized (ActorNativeArena.this) {
                checked();
                if (offset < 0 || length < 0 || offset > capacity || length > capacity - offset) {
                    throw new IndexOutOfBoundsException("read-only lease outside native region");
                }
                readLeases = Math.addExact(readLeases, 1);
                return new ReadOnlyLease(offset, length);
            }
        }

        public final class ReadOnlyLease implements AutoCloseable {
            private final int base;
            private final int length;
            private boolean released;

            private ReadOnlyLease(int base, int length) {
                this.base = base;
                this.length = length;
            }

            public int length() {
                synchronized (ActorNativeArena.this) {
                    if (released) throw new IllegalStateException("read-only lease is closed");
                    Region.this.checked();
                    return length;
                }
            }

            private ByteBuffer readChecked(int relative, int width) {
                if (released) throw new IllegalStateException("read-only lease is closed");
                ByteBuffer bytes = Region.this.checked();
                if (relative < 0 || relative > length || width > length - relative) {
                    throw new IndexOutOfBoundsException("read outside leased native slice");
                }
                return bytes;
            }

            public long readLong(int relative) {
                synchronized (ActorNativeArena.this) {
                    return readChecked(relative, Long.BYTES).getLong(base + relative);
                }
            }

            public double readDouble(int relative) {
                synchronized (ActorNativeArena.this) {
                    return readChecked(relative, Double.BYTES).getDouble(base + relative);
                }
            }

            @Override public void close() {
                synchronized (ActorNativeArena.this) {
                    if (released) return;
                    // Physical actor finalization revokes all native storage;
                    // outstanding Java facades must not resurrect it.
                    if (!ActorNativeArena.this.closed) {
                        checkAccess();
                        readLeases--;
                    }
                    released = true;
                }
            }
        }

        private void revoke() {
            if (memory == null) return;
            releaseNative(memory);
            memory = null;
            committed -= capacity;
            release.run();
        }
        @Override public void close() {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                if (readLeases != 0) {
                    throw new IllegalStateException("cannot release a leased native region");
                }
                revoke();
                regions.remove(this);
            }
        }
    }

    private void checkAccess() {
        if (closed) throw new IllegalStateException("actor native arena is closed");
        checkOwner.run();
    }

    private long reserveActorOffset(int bytes, int alignment) {
        if (bytes < 0 || alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("invalid actor-relative allocation");
        }
        long mask = alignment - 1L;
        long base = Math.addExact(nextActorOffset, mask) & ~mask;
        nextActorOffset = Math.addExact(base, bytes);
        return base;
    }

    private long allocate(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative native allocation");
        // Even a zero-field object has a native identity allocation.
        int size = Math.max(8, Math.addExact(bytes, 7) & ~7);
        if (slabs.isEmpty() || slabs.get(slabs.size() - 1).capacity() - tail < size) {
            long remaining = limit - committed;
            if (size > remaining) throw new IllegalStateException("actor native arena limit exceeded");
            int capacity = (int) Math.max(size, Math.min(SLAB_BYTES, remaining));
            Runnable release = budget.reserve(capacity);
            ByteBuffer slab = null;
            try {
                // Grow both metadata arrays before owning native bytes, so an
                // OOME cannot leave a freed slab published in only one list.
                slabs.ensureCapacity(slabs.size() + 1);
                slabBases.ensureCapacity(slabBases.size() + 1);
                releases.ensureCapacity(releases.size() + 1);
                long slabBase = reserveActorOffset(capacity, Long.BYTES);
                NativeCarrierExecutor.ensureNativeLibraryLoaded();
                slab = allocateNative(capacity);
                if (slab == null) throw new OutOfMemoryError("native actor arena allocation failed");
                slab.order(ByteOrder.LITTLE_ENDIAN);
                slabs.add(slab);
                slabBases.add(slabBase);
                releases.add(release);
            } catch (RuntimeException | Error failure) {
                if (slab != null) releaseNative(slab);
                release.run();
                throw failure;
            }
            committed += capacity;
            tail = 0;
        }
        long address = Math.addExact(slabBases.get(slabBases.size() - 1), tail);
        tail += size;
        allocated += size;
        return address;
    }

    private int slabIndex(long address) {
        for (int i = slabBases.size() - 1; i >= 0; i--) {
            long base = slabBases.get(i);
            if (address >= base) {
                long relative = address - base;
                if (relative < slabs.get(i).capacity()) return i;
                break;
            }
        }
        throw new IllegalStateException("invalid actor-relative slab offset");
    }
    private ByteBuffer slab(long address) { return slabs.get(slabIndex(address)); }
    private int offset(long address) {
        int index = slabIndex(address);
        return Math.toIntExact(address - slabBases.get(index));
    }
    private long read(long address, int relative) {
        return slab(address).getLong(Math.addExact(offset(address), relative));
    }
    private void write(long address, int relative, long value) {
        slab(address).putLong(Math.addExact(offset(address), relative), value);
    }

    private long storeText(String value) {
        long address = allocate(Math.multiplyExact(value.length(), 2));
        overwriteText(address, value);
        return address;
    }

    private void overwriteText(long address, String value) {
        ByteBuffer data = slab(address);
        int start = offset(address);
        for (int i = 0; i < value.length(); i++) data.putChar(start + i * 2, value.charAt(i));
    }

    private static boolean isTextTag(long tag) {
        return tag == STRING || tag == BIG_INTEGER || tag == BIG_DECIMAL
                || tag == PROCESS_SYMBOL || tag == STABLE_SYMBOL;
    }

    private String loadText(long address, int length) {
        char[] data = new char[length];
        ByteBuffer source = slab(address);
        int start = offset(address);
        for (int i = 0; i < length; i++) data[i] = source.getChar(start + i * 2);
        return new String(data);
    }

    private Object loadValue(long slot, Object uninitialized) {
        long tag = read(slot, 0), bits = read(slot, 8);
        if (tag == NULL) return null;
        if (tag == UNINITIALIZED) return uninitialized;
        if (tag == BOOLEAN) return bits != 0;
        if (tag == BYTE) return (byte) bits;
        if (tag == SHORT) return (short) bits;
        if (tag == INTEGER) return (int) bits;
        if (tag == LONG) return bits;
        if (tag == FLOAT) return Float.intBitsToFloat((int) bits);
        if (tag == DOUBLE) return Double.longBitsToDouble(bits);
        if (tag == CHARACTER) return (char) bits;
        if (tag == REFERENCE) {
            NativeValue value = referencesByOffset.get(bits);
            if (value == null || value.nativeArena() != this || value.nativeOffset() != bits) {
                throw new SecurityException("stale or moved actor resource reference");
            }
            return value;
        }
        String text = loadText(bits, Math.toIntExact(read(slot, 16)));
        if (tag == STRING) return text;
        if (tag == BIG_INTEGER) return new BigInteger(text);
        if (tag == BIG_DECIMAL) return new BigDecimal(text);
        if (tag == PROCESS_SYMBOL) return OresSymbol.process(text);
        if (tag == STABLE_SYMBOL) return OresSymbol.stable(text);
        throw new IllegalStateException("invalid native actor field tag");
    }

    private void storeValue(long slot, Object value, Object uninitialized) {
        long tag, bits = 0, length = 0;
        String text = null;
        if (value == null) tag = NULL;
        else if (value == uninitialized) tag = UNINITIALIZED;
        else if (value instanceof Boolean v) { tag = BOOLEAN; bits = v ? 1 : 0; }
        else if (value instanceof Byte v) { tag = BYTE; bits = v; }
        else if (value instanceof Short v) { tag = SHORT; bits = v; }
        else if (value instanceof Integer v) { tag = INTEGER; bits = v; }
        else if (value instanceof Long v) { tag = LONG; bits = v; }
        else if (value instanceof Float v) { tag = FLOAT; bits = Float.floatToRawIntBits(v); }
        else if (value instanceof Double v) { tag = DOUBLE; bits = Double.doubleToRawLongBits(v); }
        else if (value instanceof Character v) { tag = CHARACTER; bits = v; }
        else if (value instanceof String v) { tag = STRING; text = v; }
        else if (value instanceof BigInteger v) { tag = BIG_INTEGER; text = v.toString(); }
        else if (value instanceof BigDecimal v) { tag = BIG_DECIMAL; text = v.toString(); }
        else if (value instanceof OresSymbol v) {
            tag = v.kind() == OresSymbol.Kind.PROCESS ? PROCESS_SYMBOL : STABLE_SYMBOL;
            text = v.name();
        } else if (value instanceof NativeValue v) {
            if (v.nativeArena() != this) throw new SecurityException("foreign actor arena reference");
            long address = v.nativeOffset();
            if (address < 0) {
                throw new SecurityException("actor-native value has no actor-relative address");
            }
            tag = REFERENCE;
            Long prior = referenceOffsets.putIfAbsent(v, address);
            if (prior != null && prior.longValue() != address) {
                throw new SecurityException("actor-native value changed logical address");
            }
            NativeValue occupant = referencesByOffset.putIfAbsent(address, v);
            if (occupant != null && occupant != v) {
                throw new SecurityException("duplicate actor-relative native address");
            }
            bits = address;
        } else {
            throw new IllegalArgumentException("actor field requires native value lowering for "
                    + value.getClass().getName() + "; managed guest payload fallback is disabled");
        }
        if (text != null) {
            // In-place replacement is safe: text payloads are never aliased
            // between field slots, and generic reads materialize detached values.
            // Preserve the old value if the new payload requires allocation
            // and the actor's native budget is exhausted.
            long oldTag = read(slot, 0);
            if (isTextTag(oldTag) && read(slot, 16) == text.length()) {
                bits = read(slot, 8);
                overwriteText(bits, text);
            } else {
                bits = storeText(text);
            }
            length = text.length();
        }
        // Publish only after validation/allocation succeeds; failed assignment
        // leaves the old slot intact.
        write(slot, 8, bits);
        write(slot, 16, length);
        write(slot, 0, tag);
    }

    private final class Fields extends AbstractMap<String, Object> implements NativeValue {
        private final List<String> names;
        private final long slots;
        private final Object uninitialized;

        Fields(List<String> names, long slots, Object uninitialized) {
            this.names = names;
            this.slots = slots;
            this.uninitialized = uninitialized;
        }

        @Override public ActorNativeArena nativeArena() { return ActorNativeArena.this; }
        @Override public long nativeOffset() {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                return slots;
            }
        }

        @Override public int size() {
            synchronized (ActorNativeArena.this) { checkAccess(); return names.size(); }
        }
        @Override public boolean containsKey(Object key) {
            synchronized (ActorNativeArena.this) { checkAccess(); return names.contains(key); }
        }
        @Override public Object get(Object key) {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                int index = names.indexOf(key);
                return index < 0 ? null : loadValue(slots + (long) index * SLOT_BYTES, uninitialized);
            }
        }
        @Override public Object put(String key, Object value) {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                int index = names.indexOf(key);
                if (index < 0) throw new IllegalArgumentException("unknown native field " + key);
                long slot = slots + (long) index * SLOT_BYTES;
                Object previous = loadValue(slot, uninitialized);
                storeValue(slot, value, uninitialized);
                return previous;
            }
        }
        @Override public Set<Entry<String, Object>> entrySet() {
            synchronized (ActorNativeArena.this) { checkAccess(); }
            return new AbstractSet<>() {
                @Override public int size() { return Fields.this.size(); }
                @Override public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        private int index;
                        @Override public boolean hasNext() { return index < Fields.this.size(); }
                        @Override public Entry<String, Object> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            String key = names.get(index++);
                            return new SimpleImmutableEntry<>(key, Fields.this.get(key));
                        }
                    };
                }
            };
        }
    }

    /** Called only after the runtime's actor/async physical quiescence barrier. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (linearMemory != null) linearMemory.forgetReleasedPages();
        for (Region region : regions) region.revoke();
        regions.clear();
        for (ByteBuffer slab : slabs) releaseNative(slab);
        for (Runnable release : releases) release.run();
        slabs.clear();
        slabBases.clear();
        releases.clear();
        referencesByOffset.clear();
        referenceOffsets.clear();
        committed = 0;
    }

    private static native ByteBuffer allocateNative(int bytes);
    private static native void releaseNative(ByteBuffer memory);
}
