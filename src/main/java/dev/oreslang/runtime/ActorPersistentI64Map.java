package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

/**
 * One actor-owned, bounded native i64-to-i64 state field. The only public
 * read/write operations live on a temporary Access object passed to critical().
 * Nested synchronous critical calls on one actor turn are allowed.\n * The map's slots are allocated in ActorArena's persistent FFM region and
 * survive mailbox messages; the Access object does not survive its callback.
 *
 * This is a synchronous host prototype, NOT a general Oreslang Map<K,V>,
 * the scheduler's logical CriticalGate, or suspendable handle_state lowering.
 * A JVM monitor is held only for this non-suspending callback; never extend
 * it across await, reentrancy boundaries, or a carrier migration.
 */
public final class ActorPersistentI64Map implements AutoCloseable {
    private static final long EMPTY = 0L;
    private static final long LIVE = 1L;
    private static final long DELETED = 2L;
    private final ActorArena arena;
    private final int capacity;
    private final int maxEntries;
    private final ExplicitNativeI64Region.Slot[] keys;
    private final ExplicitNativeI64Region.Slot[] values;
    private final ExplicitNativeI64Region.Slot[] status;
    private final ArrayList<ExplicitNativeI64Region.Slot> allocated;
    private Access active;
    private int size;
    private boolean closed;

    ActorPersistentI64Map(ActorArena arena, int capacity) {
        this.arena = Objects.requireNonNull(arena, "arena");
        if (capacity < 4 || capacity > 21845) {
            throw new IllegalArgumentException("native map capacity must be 4..21845");
        }
        arena.requireOwnerForState();
        this.capacity = capacity;
        this.maxEntries = Math.max(1, capacity - capacity / 4);
        this.keys = new ExplicitNativeI64Region.Slot[capacity];
        this.values = new ExplicitNativeI64Region.Slot[capacity];
        this.status = new ExplicitNativeI64Region.Slot[capacity];
        this.allocated = new ArrayList<>(Math.multiplyExact(capacity, 3));
        try {
            for (int index = 0; index < capacity; index++) {
                keys[index] = allocateZero();
                values[index] = allocateZero();
                status[index] = allocateZero();
            }
        } catch (RuntimeException | Error failure) {
            for (int index = allocated.size() - 1; index >= 0; index--) {
                try { arena.dropPersistent(allocated.get(index)); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private ExplicitNativeI64Region.Slot allocateZero() {
        var slot = arena.allocatePersistent(0L);
        allocated.add(slot);
        return slot;
    }

    /**
     * Exclusive, non-suspending access to native persistent actor state.
     * The actor mailbox is expected to be single-flight. Nested callbacks
     * temporarily fence the outer Access view; finally restores it. This
     * host monitor is NOT a scheduler-owned continuation lock.
     * Never block a carrier or retain an Access across await/FFI.
     */
    public synchronized <R> R critical(Function<? super Access, ? extends R> body) {
        Objects.requireNonNull(body, "critical body");
        requireActiveOwner();
        Access previous = active;
        Access access = new Access();
        active = access;
        try {
            return body.apply(access);
        } finally {
            access.live = false;
            active = previous;
        }
    }

    /** Host-side spelling of the sealed Oreslang self.handle_state(callback) intrinsic.
     * The compiler must additionally reject suspension, capability escape,
     * mutable result aliases and guest overrides; this host API cannot prove those.
     */
    public <R> R handleState(Function<? super Access, ? extends R> callback) {
        return critical(callback);
    }

    private void requireActiveOwner() {
        arena.requireOwnerForState();
        if (closed) throw new IllegalStateException("actor persistent state map closed");
    }

    private void requireAccess(Access access) {
        requireActiveOwner();
        if (active != access || !access.live) {
            throw new IllegalStateException("actor state access outside critical scope");
        }
    }

    private int home(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) Long.remainderUnsigned(h, capacity);
    }

    private long statusAt(int index) { return arena.readPersistent(status[index]); }
    private long keyAt(int index) { return arena.readPersistent(keys[index]); }
    private long valueAt(int index) { return arena.readPersistent(values[index]); }

    private int existing(long key) {
        int start = home(key);
        for (int probe = 0; probe < capacity; probe++) {
            int index = (start + probe) % capacity;
            long state = statusAt(index);
            if (state == EMPTY) return -1;
            if (state == LIVE && keyAt(index) == key) return index;
        }
        return -1;
    }

    /** A temporary borrow-like view; never exposes native slots or segments. */
    public final class Access {
        private boolean live = true;
        private Access() { }

        public int size() {
            requireAccess(this);
            return size;
        }

        public boolean containsKey(long key) {
            requireAccess(this);
            return existing(key) >= 0;
        }

        public long getOrDefault(long key, long fallback) {
            requireAccess(this);
            int index = existing(key);
            return index < 0 ? fallback : valueAt(index);
        }

        /** Fails before mutation when the configured maximum load is reached. */
        public void put(long key, long value) {
            requireAccess(this);
            int start = home(key);
            int tombstone = -1;
            for (int probe = 0; probe < capacity; probe++) {
                int index = (start + probe) % capacity;
                long state = statusAt(index);
                if (state == LIVE) {
                    if (keyAt(index) == key) {
                        arena.writePersistent(values[index], value);
                        return;
                    }
                } else if (state == DELETED) {
                    if (tombstone < 0) tombstone = index;
                } else if (state == EMPTY) {
                    insert(tombstone < 0 ? index : tombstone, key, value);
                    return;
                } else {
                    throw new IllegalStateException("invalid native map slot state");
                }
            }
            if (tombstone >= 0) {
                insert(tombstone, key, value);
                return;
            }
            throw new IllegalStateException("native actor state map full");
        }

        private void insert(int index, long key, long value) {
            if (size >= maxEntries) throw new IllegalStateException("native actor state map load limit reached");
            // Publish LIVE last: an incomplete insert must never be visible.
            arena.writePersistent(keys[index], key);
            arena.writePersistent(values[index], value);
            arena.writePersistent(status[index], LIVE);
            size++;
        }

        public boolean remove(long key) {
            requireAccess(this);
            int index = existing(key);
            if (index < 0) return false;
            // Make entry unreachable before zeroizing its payload.
            arena.writePersistent(status[index], DELETED);
            arena.writePersistent(keys[index], 0L);
            arena.writePersistent(values[index], 0L);
            size--;
            return true;
        }

        public void clear() {
            requireAccess(this);
            for (int index = 0; index < capacity; index++) {
                // Mark non-live before erasing for fail-closed exception paths.
                arena.writePersistent(status[index], EMPTY);
                arena.writePersistent(keys[index], 0L);
                arena.writePersistent(values[index], 0L);
            }
            size = 0;
        }
    }

    public synchronized boolean closed() { return closed || arena.closed(); }

    @Override public synchronized void close() {
        if (closed) return;
        requireActiveOwner();
        if (active != null) {
            throw new IllegalStateException("cannot close actor state during critical section");
        }
        // Retire all map-owned slots in reverse order. Remove only after a
        // successful drop so a failed close can be retried safely.
        for (int index = allocated.size() - 1; index >= 0; index--) {
            var slot = allocated.get(index);
            if (slot != null) {
                arena.dropPersistent(slot);
                allocated.set(index, null);
            }
        }
        closed = true;
    }
}
