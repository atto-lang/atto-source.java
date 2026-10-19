package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted-host reference for a typed ETS-style public set table.
 *
 * <p>Atomic operations are linearizable per key. Entries admit only immutable scalar
 * keys/values, so a lookup cannot leak a shared mutable Java object. This class
 * is intentionally NOT exposed as an Oreslang guest intrinsic: guest callers
 * require authenticated table ACLs, actor-lifetime ownership, quota enforcement,
 * and nonparking scheduler integration before admission.</p>
 */
public final class OresEtsTable<K, V> implements AutoCloseable {
    private static final long CLOSED = 1L << 63;
    private static final long ACTIVE_MASK = Long.MAX_VALUE;
    private final ConcurrentHashMap<K, V> entries = new ConcurrentHashMap<>();
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicInteger usedEntries = new AtomicInteger();
    private final int maxEntries;

    public OresEtsTable(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    private static void requireImmutableScalar(Object value) {
        Objects.requireNonNull(value, "table keys and values cannot be null");
        if (value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Character || value instanceof BigInteger
                || value instanceof BigDecimal) return;
        throw new IllegalArgumentException("ETS reference admits only immutable scalar keys and values");
    }

    private void enter() {
        // Host-only guard: CHM can synchronize/park when keys collide, so an
        // actor carrier must not invoke this reference directly.
        if (ActorRuntime.isActorCarrierThread()) {
            throw new IllegalStateException("host ETS reference is not a nonparking guest table");
        }
        for (;;) {
            long state = lifecycle.get();
            if ((state & CLOSED) != 0) throw new IllegalStateException("ETS table is closed");
            if ((state & ACTIVE_MASK) == ACTIVE_MASK)
                throw new IllegalStateException("too many active ETS operations");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }

    private void exit() {
        long before = lifecycle.getAndDecrement();
        if ((before & ACTIVE_MASK) == 0) throw new AssertionError("unbalanced ETS operation");
        if (before == CLOSED + 1) entries.clear();
    }

    /** Snapshot of an immutable value, not a borrowed mutable reference. */
    public Optional<V> lookup(K key) {
        requireImmutableScalar(key);
        enter();
        try { return Optional.ofNullable(entries.get(key)); }
        finally { exit(); }
    }

    private void reserveEntry() {
        for (;;) {
            int used = usedEntries.get();
            if (used >= maxEntries) throw new IllegalStateException("ETS table entry quota exceeded");
            if (usedEntries.compareAndSet(used, used + 1)) return;
        }
    }

    /** Insert or replace one key atomically. */
    public Optional<V> insert(K key, V value) {
        requireImmutableScalar(key);
        requireImmutableScalar(value);
        enter();
        try {
            var previous = new AtomicReference<V>();
            entries.compute(key, (k, old) -> {
                if (old == null) reserveEntry();
                previous.set(old);
                return value;
            });
            return Optional.ofNullable(previous.get());
        } finally { exit(); }
    }

    /** Insert if missing; returns true only for the winner. */
    public boolean insertNew(K key, V value) {
        requireImmutableScalar(key);
        requireImmutableScalar(value);
        enter();
        try {
            var inserted = new AtomicBoolean();
            entries.computeIfAbsent(key, k -> {
                reserveEntry();
                inserted.set(true);
                return value;
            });
            return inserted.get();
        } finally { exit(); }
    }

    /** Atomic per-key conditional replacement, using value equality. */
    public boolean compareExchange(K key, V expected, V replacement) {
        requireImmutableScalar(key);
        requireImmutableScalar(expected);
        requireImmutableScalar(replacement);
        enter();
        try { return entries.replace(key, expected, replacement); }
        finally { exit(); }
    }

    /** Remove a single key and return the old immutable value. */
    public Optional<V> take(K key) {
        requireImmutableScalar(key);
        enter();
        try {
            V removed = entries.remove(key);
            if (removed != null) usedEntries.decrementAndGet();
            return Optional.ofNullable(removed);
        } finally { exit(); }
    }

    public int size() {
        enter();
        try { return usedEntries.get(); }
        finally { exit(); }
    }

    /** Weakly consistent snapshot, explicitly not a cross-key transaction. */
    public Map<K,V> snapshot() {
        enter();
        try { return Map.copyOf(entries); }
        finally { exit(); }
    }

    public boolean isClosed() { return (lifecycle.get() & CLOSED) != 0; }

    @Override
    public void close() {
        for (;;) {
            long state = lifecycle.get();
            if ((state & CLOSED) != 0) return;
            if (lifecycle.compareAndSet(state, state | CLOSED)) {
                // Ongoing operations retain references until they exit; the last
                // one clears storage. Never wait on an actor carrier here.
                if (state == 0) entries.clear();
                return;
            }
        }
    }
}
