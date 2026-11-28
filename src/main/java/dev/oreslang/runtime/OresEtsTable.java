package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
    private final AtomicLong usedBytes = new AtomicLong();
    private final int maxEntries;
    private final long maxBytes;

    public OresEtsTable(int maxEntries) {
        this(maxEntries, 64L * 1024 * 1024);
    }

    public OresEtsTable(int maxEntries, long maxBytes) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /**
     * Bound immutable scalar storage BEFORE hashing or reserving a table slot.
     * Symbol identity is logical (kind + NFC name), not a permanent intern id.
     * This trusted-host table is not an untrusted actor admission boundary.
     */
    private static long scalarBytes(Object value) {
        Objects.requireNonNull(value, "table keys and values cannot be null");
        if (value instanceof OresSymbol symbol) {
            return symbol.name().getBytes(StandardCharsets.UTF_8).length + 1L;
        }
        if (value instanceof String str) {
            if (str.length() > 4096) {
                throw new IllegalArgumentException("ETS String scalar too large");
            }
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= str.length() || !Character.isLowSurrogate(str.charAt(++i))) {
                        throw new IllegalArgumentException("ETS String has malformed UTF-16");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw new IllegalArgumentException("ETS String has malformed UTF-16");
                }
            }
            int bytes = str.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 4096) throw new IllegalArgumentException("ETS String scalar too large");
            return bytes;
        }
        if (value instanceof BigInteger integer) {
            if (integer.bitLength() > 4096) {
                throw new IllegalArgumentException("ETS BigInteger scalar too large");
            }
            return integer.toByteArray().length;
        }
        if (value instanceof BigDecimal decimal) {
            if (decimal.unscaledValue().bitLength() > 4096 || Math.abs((long) decimal.scale()) > 4096) {
                throw new IllegalArgumentException("ETS BigDecimal scalar too large");
            }
            return decimal.unscaledValue().toByteArray().length + 4L;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Character) return 8L;
        throw new IllegalArgumentException("ETS reference admits only immutable scalar keys and values");
    }

    /** Positive reservations are atomic; negative refunds follow map publication. */
    private void reserveBytes(long additional) {
        if (additional <= 0) return;
        for (;;) {
            long current = usedBytes.get();
            if (additional > maxBytes - current) {
                throw new IllegalStateException("ETS table byte quota exceeded");
            }
            if (usedBytes.compareAndSet(current, current + additional)) return;
        }
    }

    private void retireContents() {
        entries.clear();
        usedEntries.set(0);
        usedBytes.set(0);
    }

    private static void requireHost() {
        // A direct guest turn may run on a host-backed executor while
        // remaining an actor or CONTROL execution. Only a trusted host
        // thread may use CHM (which is not proven nonparking).
        if (ActorRuntime.isActorCarrierThread()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException("host ETS reference is not a nonparking guest table");
        }
    }

    private void enter() {
        requireHost();
        for (;;) {
            long state = lifecycle.get();
            if ((state & CLOSED) != 0) throw new IllegalStateException("ETS table is closed");
            if ((state & ACTIVE_MASK) == ACTIVE_MASK)
                throw new IllegalStateException("too many active ETS operations");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }

    private void exit() {
        for (;;) {
            long before = lifecycle.get();
            long active = before & ACTIVE_MASK;
            // A failed double-exit must never flip CLOSED back to open.
            if (active == 0) throw new AssertionError("unbalanced ETS operation");
            long after = (before & CLOSED) | (active - 1);
            if (lifecycle.compareAndSet(before, after)) {
                if (before == CLOSED + 1) retireContents();
                return;
            }
        }
    }

    /** Snapshot of an immutable value, not a borrowed mutable reference. */
    public Optional<V> lookup(K key) {
        scalarBytes(key);
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
        scalarBytes(key);
        scalarBytes(value);
        enter();
        try {
            final long keySize = scalarBytes(key), newSize = scalarBytes(value);
            var previous = new AtomicReference<V>();
            var refund = new AtomicLong();
            entries.compute(key, (k, old) -> {
                long oldBytes = old == null ? 0 : keySize + scalarBytes(old);
                long delta = keySize + newSize - oldBytes;
                boolean newEntry = old == null;
                if (newEntry) reserveEntry();
                try {
                    reserveBytes(delta);
                } catch (RuntimeException failure) {
                    if (newEntry) usedEntries.decrementAndGet();
                    throw failure;
                }
                if (delta < 0) refund.set(delta);
                previous.set(old);
                return value;
            });
            if (refund.get() != 0) usedBytes.addAndGet(refund.get());
            return Optional.ofNullable(previous.get());
        } finally { exit(); }
    }

    /** Insert if missing; returns true only for the winner. */
    public boolean insertNew(K key, V value) {
        scalarBytes(key);
        scalarBytes(value);
        enter();
        try {
            final long bytes = scalarBytes(key) + scalarBytes(value);
            var inserted = new AtomicBoolean();
            entries.computeIfAbsent(key, k -> {
                reserveEntry();
                try {
                    reserveBytes(bytes);
                } catch (RuntimeException failure) {
                    usedEntries.decrementAndGet();
                    throw failure;
                }
                inserted.set(true);
                return value;
            });
            return inserted.get();
        } finally { exit(); }
    }

    /** Atomic per-key conditional replacement, using value equality. */
    public boolean compareExchange(K key, V expected, V replacement) {
        scalarBytes(key);
        scalarBytes(expected);
        scalarBytes(replacement);
        enter();
        try {
            final long beforeSize = scalarBytes(expected), afterSize = scalarBytes(replacement);
            var swapped = new AtomicBoolean();
            var refund = new AtomicLong();
            entries.computeIfPresent(key, (k, old) -> {
                if (!old.equals(expected)) return old;
                long delta = afterSize - beforeSize;
                reserveBytes(delta);
                if (delta < 0) refund.set(delta);
                swapped.set(true);
                return replacement;
            });
            if (refund.get() != 0) usedBytes.addAndGet(refund.get());
            return swapped.get();
        } finally { exit(); }
    }

    /** Remove a single key and return the old immutable value. */
    public Optional<V> take(K key) {
        scalarBytes(key);
        enter();
        try {
            V removed = entries.remove(key);
            if (removed != null) {
                usedEntries.decrementAndGet();
                usedBytes.addAndGet(-(scalarBytes(key) + scalarBytes(removed)));
            }
            return Optional.ofNullable(removed);
        } finally { exit(); }
    }

    public int size() {
        enter();
        try { return usedEntries.get(); }
        finally { exit(); }
    }

    public long byteSize() {
        enter();
        try { return usedBytes.get(); }
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
        // Never retire this host reference from actor or CONTROL execution;
        // even an empty ConcurrentHashMap.clear() is not scheduler-proof.
        requireHost();
        for (;;) {
            long state = lifecycle.get();
            if ((state & CLOSED) != 0) return;
            if (lifecycle.compareAndSet(state, state | CLOSED)) {
                // Ongoing operations retain references until they exit; the last
                // one clears storage. Never wait on an actor carrier here.
                if (state == 0) retireContents();
                return;
            }
        }
    }
}
