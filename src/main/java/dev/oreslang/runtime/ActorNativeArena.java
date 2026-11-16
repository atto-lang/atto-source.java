package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
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

    /** Privileged runtime resource stored as an opaque arena-local handle ID. */
    public interface NativeCapability {
        ActorNativeArena nativeArena();
    }

    @FunctionalInterface
    interface Budget {
        // Refund callbacks must be retryable: throwing must leave their charge
        // unchanged. A successful return commits the refund exactly once.
        Runnable reserve(int bytes);
    }

    /**
     * Weak keys with identity (not equals/hashCode) semantics. Native facades
     * such as Fields may have structural Map equality, so WeakHashMap would be
     * incorrect for provenance. Arena methods serialize access to this helper.
     */
    private static final class WeakIdentityMap<K, V> {
        private final ReferenceQueue<K> queue = new ReferenceQueue<>();
        private final HashMap<IdentityWeakReference<K>, V> values = new HashMap<>();

        V get(K key) {
            expunge();
            return values.get(new IdentityWeakReference<>(key));
        }

        V put(K key, V value) {
            expunge();
            return values.put(new IdentityWeakReference<>(key, queue), value);
        }

        V putIfAbsent(K key, V value) {
            expunge();
            return values.putIfAbsent(new IdentityWeakReference<>(key, queue), value);
        }

        V remove(K key) {
            expunge();
            return values.remove(new IdentityWeakReference<>(key));
        }

        void clear() {
            values.clear();
            while (queue.poll() != null) { }
        }

        @SuppressWarnings("unchecked")
        private void expunge() {
            IdentityWeakReference<K> stale;
            while ((stale = (IdentityWeakReference<K>) queue.poll()) != null) {
                values.remove(stale);
            }
        }
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        IdentityWeakReference(T value) {
            super(Objects.requireNonNull(value));
            identityHash = System.identityHashCode(value);
        }

        IdentityWeakReference(T value, ReferenceQueue<T> queue) {
            super(Objects.requireNonNull(value), queue);
            identityHash = System.identityHashCode(value);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference<?> reference)) return false;
            Object value = get();
            return value != null && value == reference.get();
        }
    }

    private static final int SLOT_BYTES = 24;
    private static final int TASK_FRAME_BYTES = 40;
    private static final int LIST_HEADER_BYTES = 32;
    private static final int LIST_CHUNK_SLOTS = 16;
    private static final int LIST_CHUNK_HEADER_BYTES = Long.BYTES;
    private static final int LIST_CHUNK_BYTES =
            LIST_CHUNK_HEADER_BYTES + LIST_CHUNK_SLOTS * SLOT_BYTES;
    private static final long TASK_FRAME_MAGIC = 0x4f5245535441534bL; // "ORESTASK"
    private static final long LIST_MAGIC = 0x4f5245534c495354L; // "ORESLIST"
    private static final long NO_OFFSET = -1L;
    private static final int SLAB_BYTES = 4096;
    private static final long NULL = 0, UNINITIALIZED = 1, BOOLEAN = 2,
            BYTE = 3, SHORT = 4, INTEGER = 5, LONG = 6, FLOAT = 7, DOUBLE = 8,
            CHARACTER = 9, STRING = 10, REFERENCE = 11, BIG_INTEGER = 12,
            BIG_DECIMAL = 13, PROCESS_SYMBOL = 14, STABLE_SYMBOL = 15,
            CAPABILITY = 16;

    private final Runnable checkOwner;
    private final Budget budget;
    private final long limit;
    private final ArrayList<ByteBuffer> slabs = new ArrayList<>();
    private final ArrayList<Long> slabBases = new ArrayList<>();
    private final ArrayList<Runnable> releases = new ArrayList<>();
    // Refunds from failed, never-published allocations must remain retryable.
    // Capacity is reserved before charging so retention itself cannot allocate.
    private final ArrayList<Runnable> pendingBudgetRefunds = new ArrayList<>();
    /** Native object addresses may only be admitted after the arena issues their authority. */
    private final WeakIdentityMap<NativeValue, Long> issuedNativeOffsets = new WeakIdentityMap<>();
    private final WeakIdentityMap<NativeValue, Long> referenceOffsets = new WeakIdentityMap<>();
    private final Map<Long, NativeValue> referencesByOffset = new HashMap<>();
    private final Map<Long, Integer> referenceRefCounts = new HashMap<>();
    /** Only runtime-issued capabilities may enter guest-native slots. */
    private final WeakIdentityMap<NativeCapability, Boolean> issuedNativeCapabilities = new WeakIdentityMap<>();
    private final IdentityHashMap<NativeCapability, Long> capabilityIds = new IdentityHashMap<>();
    private final Map<Long, NativeCapability> capabilitiesById = new HashMap<>();
    private final Map<Long, Integer> capabilityRefCounts = new HashMap<>();
    private long nextCapabilityId = 1L;
    private final Set<Region> regions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Monotonic actor-relative address allocator; retired addresses are never reused. */
    private long nextActorOffset;
    private int tail;
    private long committed;
    private long allocated;
    private boolean closed;
    private boolean cleanupComplete;
    private boolean cleanupInProgress;
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
        Fields fields = new Fields(layout, slots, uninitialized);
        // calloc zeroes native bytes to the NULL tag. A declared-but-not-yet
        // initialized field is semantically distinct from null, so publish the
        // explicit UNINITIALIZED tag for every fixed slot before exposing the
        // field facade.
        for (int index = 0; index < layout.size(); index++) {
            storeValue(slots + (long) index * SLOT_BYTES, uninitialized, uninitialized);
        }
        issuedNativeOffsets.put(fields, slots);
        return fields;
    }

    /**
     * Explicitly attest a runtime-owned facade to an allocation issued here.
     * A claimed nativeArena/nativeOffset on an arbitrary Java object is not
     * sufficient to grant actor memory authority.
     */
    public synchronized void registerNativeFacade(NativeValue facade, NativeValue backing) {
        checkAccess();
        Objects.requireNonNull(facade, "facade");
        Long issued = issuedNativeOffsets.get(backing);
        if (!(backing instanceof Fields) || issued == null
                || backing.nativeArena() != this || backing.nativeOffset() != issued
                || facade.nativeArena() != this || facade.nativeOffset() != issued) {
            throw new SecurityException("native facade backing was not issued by this actor arena");
        }
        Long existing = issuedNativeOffsets.putIfAbsent(facade, issued);
        if (existing != null && !existing.equals(issued)) {
            throw new SecurityException("native facade attempted to change actor-relative address");
        }
    }

    /**
     * Package-private issuance boundary for trusted core/stdlib runtime code.
     * Merely implementing the public NativeCapability interface or returning
     * this actor's arena must never grant authority to guest-native storage.
     */
    synchronized void issueNativeCapability(NativeCapability capability) {
        checkAccess();
        issuedNativeCapabilities.put(Objects.requireNonNull(capability), Boolean.TRUE);
    }

    /**
     * Actor-owned list storage foundation. Public collection policy remains in
     * Oreslang stdlib; this class owns only bounded native element storage.
     * Size and chunk links are authoritative native words. Element payloads use
     * the same tagged slots/reference provenance as actor object fields.
     */
    public synchronized NativeList nativeList() {
        checkAccess();
        long header = allocate(LIST_HEADER_BYTES);
        write(header, 0, LIST_MAGIC);
        write(header, 8, 0L);
        write(header, 16, NO_OFFSET);
        write(header, 24, NO_OFFSET);
        NativeList list = new NativeList(header);
        issuedNativeOffsets.put(list, header);
        return list;
    }

    public final class NativeList extends java.util.AbstractList<Object>
            implements NativeValue {
        private final long header;

        private NativeList(long header) {
            this.header = header;
        }

        @Override public ActorNativeArena nativeArena() { return ActorNativeArena.this; }

        @Override public long nativeOffset() {
            synchronized (ActorNativeArena.this) {
                checkList();
                return header;
            }
        }

        @Override public int size() {
            synchronized (ActorNativeArena.this) {
                checkList();
                return Math.toIntExact(read(header, 8));
            }
        }

        @Override public Object get(int index) {
            synchronized (ActorNativeArena.this) {
                checkIndex(index);
                return loadValue(elementSlot(index), null);
            }
        }

        @Override public Object set(int index, Object value) {
            synchronized (ActorNativeArena.this) {
                checkIndex(index);
                long slot = elementSlot(index);
                Object previous = loadValueForOverwrite(slot, null);
                storeValue(slot, value, null);
                return previous;
            }
        }

        @Override public void add(int index, Object value) {
            synchronized (ActorNativeArena.this) {
                checkList();
                int currentSize = Math.toIntExact(read(header, 8));
                if (index != currentSize) {
                    throw new UnsupportedOperationException(
                            "native actor List currently supports append insertion only");
                }

                // Reject unsupported or foreign payloads before reserving a
                // new native chunk. Otherwise each rejected append can consume
                // lifetime arena budget without changing the list length.
                if (!canStorePersistentValue(value, null)) {
                    throw new IllegalArgumentException("native actor List element requires native value lowering");
                }
                int inChunk = currentSize % LIST_CHUNK_SLOTS;
                long chunk = read(header, 24);
                if (inChunk == 0) {
                    // Retain the native chunk chain across clear(): clearing
                    // logical elements must not repeatedly consume new native
                    // memory or recycle their stable actor-relative addresses.
                    long next;
                    if (currentSize == 0) {
                        next = read(header, 16);
                    } else {
                        if (chunk == NO_OFFSET) {
                            throw new IllegalStateException("native List tail is missing");
                        }
                        next = read(chunk, 0);
                    }
                    if (next == NO_OFFSET) {
                        next = allocate(LIST_CHUNK_BYTES);
                        write(next, 0, NO_OFFSET);
                    }
                    // Store before publishing the newly active chunk. Invalid
                    // values/quota failures cannot advance the logical list.
                    storeValue(next + LIST_CHUNK_HEADER_BYTES, value, null);
                    if (currentSize == 0) {
                        write(header, 16, next);
                    } else {
                        write(chunk, 0, next);
                    }
                    write(header, 24, next);
                } else {
                    if (chunk == NO_OFFSET) {
                        throw new IllegalStateException("native List tail is missing");
                    }
                    long slot = chunk + LIST_CHUNK_HEADER_BYTES
                            + (long) inChunk * SLOT_BYTES;
                    storeValue(slot, value, null);
                }
                write(header, 8, Math.addExact((long) currentSize, 1L));
                modCount++;
            }
        }

        @Override public void clear() {
            synchronized (ActorNativeArena.this) {
                checkList();
                int count = Math.toIntExact(read(header, 8));
                for (int i = 0; i < count; i++) {
                    storeValue(elementSlot(i), null, null);
                }
                write(header, 8, 0L);
                // Preserve first-chunk ownership for bounded append reuse.
                // Active tail resets; historical chunks remain in the chain.
                write(header, 24, NO_OFFSET);
                modCount++;
            }
        }

        private void checkList() {
            checkAccess();
            if (read(header, 0) != LIST_MAGIC) {
                throw new IllegalStateException("invalid native actor List header");
            }
        }

        private void checkIndex(int index) {
            checkList();
            int count = Math.toIntExact(read(header, 8));
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(
                        "native actor List index " + index + " outside size " + count);
            }
        }

        private long elementSlot(int index) {
            int chunkIndex = index / LIST_CHUNK_SLOTS;
            int inChunk = index % LIST_CHUNK_SLOTS;
            long chunk = read(header, 16);
            for (int i = 0; i < chunkIndex; i++) {
                if (chunk == NO_OFFSET) {
                    throw new IllegalStateException("native actor List chunk chain is truncated");
                }
                chunk = read(chunk, 0);
            }
            if (chunk == NO_OFFSET) {
                throw new IllegalStateException("native actor List chunk is missing");
            }
            return chunk + LIST_CHUNK_HEADER_BYTES + (long) inChunk * SLOT_BYTES;
        }
    }

    /**
     * One actor-local scalar/reference slot for lowered lexical bindings.
     * The Java Env/Slot object may remain as metadata, but supported guest
     * payloads live in this arena and therefore inherit actor ownership,
     * lifetime, and stale-access checks.
     */
    public synchronized ValueCell valueCell(Object initial, Object uninitialized) {
        checkAccess();
        if (!supportsValue(initial, uninitialized)) {
            throw new IllegalArgumentException(
                    "actor local requires native value lowering for "
                            + (initial == null ? "null" : initial.getClass().getName()));
        }
        long slot = allocate(SLOT_BYTES);
        ValueCell cell = new ValueCell(slot, uninitialized);
        // ValueCell is evaluator-private storage metadata, not a guest-storable
        // NativeValue capability. Do not publish it into issuedNativeOffsets.
        storeValue(slot, initial, uninitialized);
        return cell;
    }

    public final class ValueCell implements NativeValue {
        private final long slot;
        private final Object uninitialized;

        private ValueCell(long slot, Object uninitialized) {
            this.slot = slot;
            this.uninitialized = uninitialized;
        }

        @Override public ActorNativeArena nativeArena() { return ActorNativeArena.this; }

        @Override public long nativeOffset() {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                return slot;
            }
        }

        public Object get() {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                return loadValue(slot, uninitialized);
            }
        }

        /**
         * Store in place when this value family has native lowering.
         * Unsupported managed-only values return false so the evaluator can
         * retain an explicit fallback until that family is lowered.
         */
        public boolean trySet(Object value) {
            synchronized (ActorNativeArena.this) {
                checkAccess();
                if (!supportsValue(value, uninitialized)) return false;
                storeValue(slot, value, uninitialized);
                return true;
            }
        }
    }

    /**
     * Preflight whether a value can be persisted in a native actor slot without
     * mutating arena state. Foreign/forged authorities are rejected rather
     * than reported as an ordinary unsupported value.
     */
    public synchronized boolean canStorePersistentValue(Object value, Object uninitialized) {
        checkAccess();
        if (value == null || value == uninitialized
                || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof Character
                || value instanceof String || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof OresSymbol) {
            return true;
        }
        if (value instanceof NativeCapability capability) {
            if (capability.nativeArena() != this) {
                throw new SecurityException("foreign actor capability");
            }
            if (issuedNativeCapabilities.get(capability) == null) {
                throw new SecurityException("unissued actor capability");
            }
            return true;
        }
        if (value instanceof NativeValue nativeValue) {
            if (nativeValue.nativeArena() != this) {
                throw new SecurityException("foreign actor arena reference");
            }
            long address = nativeValue.nativeOffset();
            Long issued = issuedNativeOffsets.get(nativeValue);
            if (address < 0 || issued == null || issued.longValue() != address) {
                throw new SecurityException("actor-native reference was not issued by this arena");
            }
            Long prior = referenceOffsets.get(nativeValue);
            if (prior != null && prior.longValue() != address) {
                throw new SecurityException("actor-native value changed logical address");
            }
            NativeValue occupant = referencesByOffset.get(address);
            if (occupant != null && occupant != nativeValue) {
                throw new SecurityException("duplicate actor-relative native address");
            }
            return true;
        }
        return false;
    }

    private boolean supportsValue(Object value, Object uninitialized) {
        if (value == null || value == uninitialized
                || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof Character
                || value instanceof String || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof OresSymbol) {
            return true;
        }
        if (value instanceof NativeCapability capability) {
            if (capability.nativeArena() != this) {
                throw new SecurityException("foreign actor capability");
            }
            // Lexical capability handles remain managed until scope-drop
            // lowering can retire opaque native handle references exactly.
            return false;
        }
        if (value instanceof NativeValue nativeValue) {
            if (nativeValue.nativeArena() != this) {
                throw new SecurityException("foreign actor arena reference");
            }
            long address = nativeValue.nativeOffset();
            Long issued = issuedNativeOffsets.get(nativeValue);
            if (address < 0 || issued == null || issued.longValue() != address) {
                throw new SecurityException("actor-native reference was not issued by this arena");
            }
            // The referent's authoritative payload is already native. Persist
            // only its arena-issued actor-relative offset in this lexical slot;
            // storeValue()/overwrite retirement maintain the facade registry.
            // This keeps actor object/list locals native across await/cooperate
            // without granting authority to an arbitrary Java reference.
            return true;
        }
        return false;
    }

    synchronized int nativeReferenceCount() { return referencesByOffset.size(); }
    synchronized int capabilityCount() { return capabilitiesById.size(); }

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
        write(base, 24, 0L); // suspension ticket generation
        write(base, 32, 0L); // active SuspensionKind, 0 when running/terminal
        return new TaskFrame(base);
    }

    public enum TaskPhase {
        NEW(0), RUNNING(1), AWAITING(2), COOPERATING(3), DONE(4), FAILED(5);

        private final long code;
        TaskPhase(long code) { this.code = code; }

        static TaskPhase fromCode(long code) {
            // Enum.values() clones an array on every invocation. Native task
            // frames decode this on resume, suspension and transition; direct
            // dispatch avoids allocating managed garbage in the hot path.
            // Long-valued native metadata must be range-checked before an
            // int switch: blind narrowing could alias 2^32+1 to RUNNING.
            if (code < 0L || code > 5L) {
                throw new IllegalStateException(
                        "invalid native actor task-frame phase " + code);
            }
            return switch ((int) code) {
                case 0 -> NEW;
                case 1 -> RUNNING;
                case 2 -> AWAITING;
                case 3 -> COOPERATING;
                case 4 -> DONE;
                case 5 -> FAILED;
                default -> throw new IllegalStateException(
                        "invalid native actor task-frame phase " + code);
            };
        }
    }

    /** Native reason bound to one exact suspended continuation generation. */
    public enum SuspensionKind {
        AWAIT(1, TaskPhase.AWAITING),
        COOPERATE(2, TaskPhase.COOPERATING);

        private final long code;
        private final TaskPhase phase;

        SuspensionKind(long code, TaskPhase phase) {
            this.code = code;
            this.phase = phase;
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

        public void beginInitialResume() {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (current != TaskPhase.NEW || read(base, 32) != 0L) {
                    throw new IllegalStateException(
                            "illegal initial native actor task resume: phase=" + current);
                }
                long generation = Math.addExact(read(base, 16), 1L);
                write(base, 16, generation);
                write(base, 8, TaskPhase.RUNNING.code);
            }
        }

        /**
         * Resume only the exact suspension generation issued by this arena.
         * Managed callbacks may carry a ticket, but cannot mint authority.
         */
        public void beginResume(SuspensionKind expectedKind, long suspensionTicket) {
            Objects.requireNonNull(expectedKind, "expectedKind");
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                long activeTicket = read(base, 24);
                long activeKind = read(base, 32);
                if (current != expectedKind.phase
                        || suspensionTicket <= 0L
                        || activeTicket != suspensionTicket
                        || activeKind != expectedKind.code) {
                    throw new IllegalStateException(
                            "stale or mismatched native actor continuation resume: phase=" + current
                                    + " expected=" + expectedKind
                                    + " ticket=" + suspensionTicket
                                    + " activeTicket=" + activeTicket);
                }
                long generation = Math.addExact(read(base, 16), 1L);
                write(base, 32, 0L);
                write(base, 16, generation);
                write(base, 8, TaskPhase.RUNNING.code);
            }
        }

        public long awaiting() { return suspend(SuspensionKind.AWAIT); }
        public long cooperating() { return suspend(SuspensionKind.COOPERATE); }

        public void done() {
            synchronized (ActorNativeArena.this) {
                transitionFromRunning(TaskPhase.DONE);
                write(base, 32, 0L);
            }
        }

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
                write(base, 32, 0L);
                write(base, 8, TaskPhase.FAILED.code);
            }
        }

        private long suspend(SuspensionKind kind) {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (current != TaskPhase.RUNNING || read(base, 32) != 0L) {
                    throw new IllegalStateException(
                            "native actor task suspension requires unsuspended RUNNING frame; phase="
                                    + current + " kind=" + kind);
                }
                long ticket = Math.addExact(read(base, 24), 1L);
                if (ticket <= 0L) throw new IllegalStateException("native actor suspension ticket exhausted");
                write(base, 24, ticket);
                write(base, 32, kind.code);
                write(base, 8, kind.phase.code);
                return ticket;
            }
        }

        private void transitionFromRunning(TaskPhase next) {
            synchronized (ActorNativeArena.this) {
                checkFrame();
                TaskPhase current = TaskPhase.fromCode(read(base, 8));
                if (current != TaskPhase.RUNNING || read(base, 32) != 0L) {
                    throw new IllegalStateException(
                            "native actor task transition requires unsuspended RUNNING; phase="
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
        retryPendingBudgetRefunds();
        pendingBudgetRefunds.ensureCapacity(pendingBudgetRefunds.size() + 1);
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
            retainFailedAdmissionRefund(release, failure);
            throw failure;
        }
    }

    public final class Region implements AutoCloseable, NativeValue {
        private ByteBuffer memory;
        private final int capacity;
        private final long baseOffset;
        private final Runnable release;
        private boolean budgetReleased;
        private boolean releasing;
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

        /**
         * Owner-checked dense numeric kernel. Bounds, writability and finite
         * scalar parameters are validated before the first mutation. One arena
         * critical section covers the full traversal, avoiding per-element
         * ownership/monitor traffic while never exposing the native buffer.
         *
         * <p>Rows are grouped into blocks. Within a block, successive logical
         * rows advance by rowStrideElements; successive blocks advance by
         * blockStrideElements. A single block represents ordinary AoS/SoA/
         * SoAoS traversal; multiple blocks represent AoSoA tiles.</p>
         */
        void integrateDoubleStridedBlocks(
                int positionBaseElement,
                int velocityBaseElement,
                int rows,
                int rowStrideElements,
                int blockRows,
                int blockStrideElements,
                double dt) {
            synchronized (ActorNativeArena.this) {
                ByteBuffer bytes = requireWritable();
                if (!Double.isFinite(dt) || rows <= 0 || rowStrideElements <= 0
                        || blockRows <= 0 || blockStrideElements < 0
                        || positionBaseElement < 0 || velocityBaseElement < 0) {
                    throw new IllegalArgumentException("invalid dense double kernel geometry");
                }
                int elements = capacity / Double.BYTES;
                if (capacity % Double.BYTES != 0) {
                    throw new IllegalStateException("double kernel requires aligned region capacity");
                }

                // Preflight the highest position/velocity element touched by
                // every block. No partial update is possible on bad geometry.
                int remaining = rows;
                int blockBase = 0;
                while (remaining > 0) {
                    int active = Math.min(blockRows, remaining);
                    long laneOffset = Math.multiplyExact(
                            (long) (active - 1), rowStrideElements);
                    long pLast = Math.addExact(
                            Math.addExact((long) positionBaseElement, blockBase),
                            laneOffset);
                    long vLast = Math.addExact(
                            Math.addExact((long) velocityBaseElement, blockBase),
                            laneOffset);
                    if (pLast < 0 || vLast < 0
                            || pLast >= elements || vLast >= elements) {
                        throw new IndexOutOfBoundsException(
                                "dense double kernel outside native region");
                    }
                    remaining -= active;
                    if (remaining != 0) {
                        blockBase = Math.addExact(blockBase, blockStrideElements);
                    }
                }

                remaining = rows;
                blockBase = 0;
                while (remaining > 0) {
                    int active = Math.min(blockRows, remaining);
                    int p = Math.addExact(positionBaseElement, blockBase);
                    int v = Math.addExact(velocityBaseElement, blockBase);
                    for (int lane = 0; lane < active; lane++) {
                        int pByte = Math.multiplyExact(p, Double.BYTES);
                        int vByte = Math.multiplyExact(v, Double.BYTES);
                        bytes.putDouble(pByte,
                                bytes.getDouble(pByte) + bytes.getDouble(vByte) * dt);
                        p = Math.addExact(p, rowStrideElements);
                        v = Math.addExact(v, rowStrideElements);
                    }
                    remaining -= active;
                    if (remaining != 0) {
                        blockBase = Math.addExact(blockBase, blockStrideElements);
                    }
                }
            }
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
                // Construct the lease before recording admission. Otherwise an
                // allocation failure leaves a phantom outstanding reader and
                // permanently prevents deterministic native reclamation.
                ReadOnlyLease lease = new ReadOnlyLease(offset, length);
                readLeases = Math.addExact(readLeases, 1);
                return lease;
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

            /**
             * Read exactly one byte of an immutable native slice, with the
             * same actor and revocation checks as word/double reads.
             * No ByteBuffer, array, address or mutable view escapes.
             */
            public byte readByte(int relative) {
                synchronized (ActorNativeArena.this) {
                    return readChecked(relative, Byte.BYTES).get(base + relative);
                }
            }

            public int readUnsignedByte(int relative) {
                return Byte.toUnsignedInt(readByte(relative));
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
                    // A read lease can be released by a foreign completion
                    // thread after its owning actor is suspended or stopped.
                    // No guest memory read/write occurs here: releasing the
                    // already-issued lease is not a fresh owner admission.
                    // Calling checkAccess() would strand the pin forever
                    // when the callback ends on a different carrier, blocking
                    // arena shutdown. Arena.close() rejects live leases, so
                    // a valid lease can never race a physical native free.
                    if (ActorNativeArena.this.closed || Region.this.memory == null
                            || readLeases <= 0) {
                        throw new IllegalStateException(
                                "native read lease outlived its region");
                    }
                    --readLeases;
                    released = true;
                }
            }
        }

        private void revoke() {
            if (releasing) throw new IllegalStateException("recursive native region release");
            releasing = true;
            try {
                if (memory != null) {
                    releaseNative(memory);
                    memory = null;
                    committed -= capacity;
                }
                if (!budgetReleased) {
                    release.run();
                    budgetReleased = true;
                }
            } finally {
                releasing = false;
            }
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

    private void retryPendingBudgetRefunds() {
        for (int i = 0; i < pendingBudgetRefunds.size(); i++) {
            Runnable release = pendingBudgetRefunds.get(i);
            if (release == null) continue;
            release.run();
            pendingBudgetRefunds.set(i, null);
        }
        pendingBudgetRefunds.clear();
    }

    private void retainFailedAdmissionRefund(Runnable release, Throwable failure) {
        try {
            release.run();
        } catch (RuntimeException | Error refundFailure) {
            pendingBudgetRefunds.add(release);
            if (refundFailure != failure) failure.addSuppressed(refundFailure);
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
        long end = Math.addExact(base, bytes);
        // Host Java pointers may be 64-bit, but guest arena offsets are a
        // *per-actor unsigned 32-bit address space* on every CPU architecture.
        // Check the aligned exclusive end before updating allocator state;
        // a failed admission must not advance the monotonic cursor or wrap.
        if (base >= (1L << 32) || end > (1L << 32))
            throw new IllegalStateException("actor 32-bit offset address space exhausted");
        nextActorOffset = end;
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
            retryPendingBudgetRefunds();
            pendingBudgetRefunds.ensureCapacity(pendingBudgetRefunds.size() + 1);
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
                retainFailedAdmissionRefund(release, failure);
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
        // slabBases is monotonic even when region/page allocations create gaps.
        // Find the greatest slab base <= address, then validate that address is
        // actually within that slab rather than an intervening logical region.
        int low = 0;
        int high = slabBases.size() - 1;
        int candidate = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long base = slabBases.get(mid);
            if (base <= address) {
                candidate = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (candidate >= 0) {
            long relative = address - slabBases.get(candidate);
            if (relative >= 0 && relative < slabs.get(candidate).capacity()) {
                return candidate;
            }
        }
        throw new IllegalStateException("invalid actor-relative slab offset");
    }

    private long read(long address, int relative) {
        int index = slabIndex(address);
        int local = Math.toIntExact(address - slabBases.get(index));
        return slabs.get(index).getLong(Math.addExact(local, relative));
    }

    private void write(long address, int relative, long value) {
        int index = slabIndex(address);
        int local = Math.toIntExact(address - slabBases.get(index));
        slabs.get(index).putLong(Math.addExact(local, relative), value);
    }

    private long storeText(String value) {
        long address = allocate(Math.multiplyExact(value.length(), 2));
        overwriteText(address, value);
        return address;
    }

    private void overwriteText(long address, String value) {
        int index = slabIndex(address);
        ByteBuffer data = slabs.get(index);
        int start = Math.toIntExact(address - slabBases.get(index));
        for (int i = 0; i < value.length(); i++) data.putChar(start + i * 2, value.charAt(i));
    }

    private static boolean isTextTag(long tag) {
        return tag == STRING || tag == BIG_INTEGER || tag == BIG_DECIMAL
                || tag == PROCESS_SYMBOL || tag == STABLE_SYMBOL;
    }

    private static long textMetadata(int length, int capacity) {
        if (length < 0 || capacity < length) {
            throw new IllegalArgumentException("invalid native text length/capacity");
        }
        return (Integer.toUnsignedLong(capacity) << 32)
                | Integer.toUnsignedLong(length);
    }

    private static int textLength(long metadata) {
        return (int) metadata;
    }

    private static int textCapacity(long metadata) {
        return (int) (metadata >>> 32);
    }

    private String loadText(long address, int length) {
        char[] data = new char[length];
        int index = slabIndex(address);
        ByteBuffer source = slabs.get(index);
        int start = Math.toIntExact(address - slabBases.get(index));
        for (int i = 0; i < length; i++) data[i] = source.getChar(start + i * 2);
        return new String(data);
    }

    /**
     * Map.put needs the prior value only as an implementation return value.
     * A moved/terminal capability or stale native reference must still be
     * replaceable so its slot registry entry can be retired. Normal reads
     * revalidate the current actor owner, native offset and capability state.
     */
    private Object loadValueForOverwrite(long slot, Object uninitialized) {
        long tag = read(slot, 0);
        if (tag == CAPABILITY) {
            return capabilitiesById.get(read(slot, 8));
        }
        if (tag == REFERENCE) {
            // Do not call NativeValue.nativeOffset(): a revoked/moved facade
            // may throw or report a different address after the original
            // slot was stored. Looking up the existing slot identity is only
            // allowed during overwrite; a regular get() remains fail-closed.
            NativeValue previous = referencesByOffset.get(
                    ActorCompressed32Reference.decodeStored(read(slot, 8)));
            if (previous == null) {
                throw new SecurityException("stale actor-native slot reference has no registry entry");
            }
            return previous;
        }
        return loadValue(slot, uninitialized);
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
            // The slot holds an unsigned compressed32 actor-relative word, not
            // a host pointer or raw 64-bit offset; nonzero high bits fail closed.
            long offset = ActorCompressed32Reference.decodeStored(bits);
            NativeValue value = referencesByOffset.get(offset);
            if (value == null || value.nativeArena() != this || value.nativeOffset() != offset) {
                throw new SecurityException("stale or moved actor resource reference");
            }
            return value;
        }
        if (tag == CAPABILITY) {
            NativeCapability capability = capabilitiesById.get(bits);
            if (capability == null || capability.nativeArena() != this) {
                throw new SecurityException("stale or moved actor capability handle");
            }
            return capability;
        }
        String text = loadText(bits, textLength(read(slot, 16)));
        if (tag == STRING) return text;
        if (tag == BIG_INTEGER) return new BigInteger(text);
        if (tag == BIG_DECIMAL) return new BigDecimal(text);
        if (tag == PROCESS_SYMBOL) return OresSymbol.process(text);
        if (tag == STABLE_SYMBOL) return OresSymbol.stable(text);
        throw new IllegalStateException("invalid native actor field tag");
    }

    private void storeValue(long slot, Object value, Object uninitialized) {
        long oldTag = read(slot, 0);
        long oldBits = read(slot, 8);
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
        } else if (value instanceof NativeCapability capability) {
            if (capability.nativeArena() != this) {
                throw new SecurityException("foreign actor capability");
            }
            if (issuedNativeCapabilities.get(capability) == null) {
                throw new SecurityException("unissued actor capability");
            }
            tag = CAPABILITY;
            Long id = capabilityIds.get(capability);
            if (id == null) {
                id = nextCapabilityId;
                nextCapabilityId = Math.addExact(nextCapabilityId, 1L);
                if (id <= 0L) throw new IllegalStateException("actor capability handle space exhausted");
                capabilityIds.put(capability, id);
                capabilitiesById.put(id, capability);
            }
            bits = id;
        } else if (value instanceof NativeValue v) {
            if (v.nativeArena() != this) throw new SecurityException("foreign actor arena reference");
            long address = v.nativeOffset();
            Long issued = issuedNativeOffsets.get(v);
            if (address < 0 || issued == null || issued.longValue() != address) {
                throw new SecurityException("actor-native reference was not issued by this arena");
            }
            tag = REFERENCE;
            // Validate both indexes before mutating either. A rejected alias
            // must not leave a phantom identity entry in the actor arena.
            Long prior = referenceOffsets.get(v);
            if (prior != null && prior.longValue() != address) {
                throw new SecurityException("actor-native value changed logical address");
            }
            NativeValue occupant = referencesByOffset.get(address);
            if (occupant != null && occupant != v) {
                throw new SecurityException("duplicate actor-relative native address");
            }
            // Encode before publishing either registry entry, so an unaligned or
            // unrepresentable address never leaves a half-published alias.
            // Cross-backend word (LLVM/.NET/Wasm): 0 = null, offset/8 + 1.
            bits = Integer.toUnsignedLong(ActorCompressed32Reference.encode(address));
            referenceOffsets.putIfAbsent(v, address);
            referencesByOffset.putIfAbsent(address, v);
        } else {
            throw new IllegalArgumentException("actor field requires native value lowering for "
                    + value.getClass().getName() + "; managed guest payload fallback is disabled");
        }
        if (text != null) {
            // Text payloads are never aliased between field/value slots.
            // Keep the original native capacity so shrink + regrow within that
            // capacity remains allocation-free without recycling logical
            // actor-relative addresses.
            int capacity = text.length();
            if (isTextTag(oldTag)) {
                long oldMetadata = read(slot, 16);
                int oldCapacity = textCapacity(oldMetadata);
                if (oldCapacity >= text.length()) {
                    bits = read(slot, 8);
                    capacity = oldCapacity;
                    overwriteText(bits, text);
                } else {
                    bits = storeText(text);
                }
            } else {
                bits = storeText(text);
            }
            length = textMetadata(text.length(), capacity);
        }
        // Publish only after validation/allocation succeeds; failed assignment
        // leaves the old slot intact. Capability registry references follow
        // slot publication and are dropped when the old handle is overwritten.
        boolean newNativeReference =
                tag == REFERENCE && (oldTag != REFERENCE || oldBits != bits);
        boolean newCapabilityReference =
                tag == CAPABILITY && (oldTag != CAPABILITY || oldBits != bits);
        if (newNativeReference) referenceRefCounts.merge(
                ActorCompressed32Reference.decodeStored(bits), 1, Math::addExact);
        if (newCapabilityReference) capabilityRefCounts.merge(bits, 1, Math::addExact);
        write(slot, 8, bits);
        write(slot, 16, length);
        write(slot, 0, tag);
        if (oldTag == REFERENCE && (tag != REFERENCE || oldBits != bits)) {
            releaseNativeReference(ActorCompressed32Reference.decodeStored(oldBits));
        }
        if (oldTag == CAPABILITY && (tag != CAPABILITY || oldBits != bits)) {
            releaseCapabilityReference(oldBits);
        }
    }

    private void releaseNativeReference(long address) {
        Integer refs = referenceRefCounts.get(address);
        if (refs == null || refs <= 0) {
            throw new IllegalStateException("actor native reference accounting underflow");
        }
        if (refs == 1) {
            referenceRefCounts.remove(address);
            NativeValue value = referencesByOffset.remove(address);
            if (value != null) referenceOffsets.remove(value);
        } else {
            referenceRefCounts.put(address, refs - 1);
        }
    }

    private void releaseCapabilityReference(long id) {
        Integer refs = capabilityRefCounts.get(id);
        if (refs == null || refs <= 0) {
            throw new IllegalStateException("actor capability reference accounting underflow");
        }
        if (refs == 1) {
            capabilityRefCounts.remove(id);
            NativeCapability capability = capabilitiesById.remove(id);
            if (capability != null) capabilityIds.remove(capability);
        } else {
            capabilityRefCounts.put(id, refs - 1);
        }
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
                Object previous = loadValueForOverwrite(slot, uninitialized);
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

    /**
     * Called only after the runtime's actor/async physical quiescence barrier.
     * Fail closed even if a buggy scheduler claims quiescence while an issued
     * native read lease still exists. Do not partially retire any region;
     * preserve the whole arena so its outstanding lease can release safely.
     */
    @Override public synchronized void close() {
        if (cleanupComplete) return;
        if (cleanupInProgress) throw new IllegalStateException("recursive native arena cleanup");
        for (Region region : regions) {
            if (region.readLeases != 0) {
                throw new IllegalStateException(
                        "actor native arena has outstanding read-only leases");
            }
        }
        closed = true;
        cleanupInProgress = true;
        try {
            if (linearMemory != null) linearMemory.forgetReleasedPages();
            for (Region region : regions) region.revoke();
            regions.clear();
            for (int i = 0; i < slabs.size(); i++) {
                ByteBuffer slab = slabs.get(i);
                if (slab != null) {
                    releaseNative(slab);
                    slabs.set(i, null);
                    committed -= slab.capacity();
                }
            }
            for (int i = 0; i < releases.size(); i++) {
                Runnable release = releases.get(i);
                if (release != null) {
                    release.run();
                    releases.set(i, null);
                }
            }
            retryPendingBudgetRefunds();
            slabs.clear();
            slabBases.clear();
            releases.clear();
            referencesByOffset.clear();
            referenceRefCounts.clear();
            referenceOffsets.clear();
            capabilityRefCounts.clear();
            capabilitiesById.clear();
            capabilityIds.clear();
            issuedNativeCapabilities.clear();
            issuedNativeOffsets.clear();
            committed = 0;
            cleanupComplete = true;
        } finally {
            cleanupInProgress = false;
        }
    }

    private static native ByteBuffer allocateNative(int bytes);
    private static native void releaseNative(ByteBuffer memory);
}

