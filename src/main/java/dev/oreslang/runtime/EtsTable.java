package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runtime-owned, bounded ETS-style table substrate.
 *
 * <p>This is deliberately not an actor heap. Keys and values are frozen through
 * the same data-only boundary used by ActorGroup events before entering the
 * table, so mutable host objects and live capabilities cannot become a shared
 * mutable-memory backdoor.</p>
 *
 * <p>The source-level otp/ets module is a separate layer. This class provides
 * the first Java/Graal runtime primitive and intentionally supports SET
 * semantics only; unsupported table kinds must fail closed at the source layer.</p>
 */
public final class EtsTable<K, V> implements AutoCloseable {
    public enum Access {
        /** Only the creating actor may read/write. Host/supervisor remains privileged. */
        PRIVATE,
        /** Runtime peers may read; only the creating actor may write. */
        PROTECTED,
        /** Runtime peers may read and write. */
        PUBLIC
    }

    public enum Kind { SET }

    private static final class StoredEntry {
        private ActorRuntime.FrozenActorGroupEventPayload key;
        private ActorRuntime.FrozenActorGroupEventPayload value;

        private StoredEntry(
                ActorRuntime.FrozenActorGroupEventPayload key,
                ActorRuntime.FrozenActorGroupEventPayload value) {
            this.key = key;
            this.value = value;
        }

        private void close() {
            key.reservation().close();
            value.reservation().close();
        }
    }

    private final ActorRuntime runtime;
    private ActorRuntime.ActorId owner;
    private long ownerEpoch = 1;
    private static final int MAX_RETIRED_OWNERS = 64;
    private final Set<ActorRuntime.ActorId> retiredOwners = new HashSet<>();
    private HeirReceipt pendingHeir;

    /**
     * Runtime-minted single-use trusted-host receipt. Not a guest token or
     * remote credential. ActorRuntime must authenticate every participant.
     */
    record HeirReceipt(
            UUID nonce, ActorRuntime.ActorId oldOwner,
            ActorRuntime.ActorId newOwner, long nextEpoch) { }

    private final int capacity;
    private final Access access;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<Object, StoredEntry> entries = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    EtsTable(
            ActorRuntime runtime,
            ActorRuntime.ActorId owner,
            int capacity,
            Access access) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (capacity <= 0) throw new IllegalArgumentException("ETS capacity must be > 0");
        this.capacity = capacity;
        this.owner = owner;
        this.access = Objects.requireNonNull(access, "access");
    }

    public Kind kind() { return Kind.SET; }
    public Access access() { return access; }
    public int capacity() { return capacity; }
    public Optional<ActorRuntime.ActorId> ownerId() {
        lock.lock();
        try { return Optional.ofNullable(owner); }
        finally { lock.unlock(); }
    }
    public long ownerEpoch() {
        lock.lock();
        try { return ownerEpoch; }
        finally { lock.unlock(); }
    }

    // CONTROL-only primitives. ActorRuntime owns physical actor retirement,
    // supervisor context and the ETS reverse-index serialization lock.
    HeirReceipt prepareHeir(ActorRuntime.ActorId oldOwner,
                            ActorRuntime.ActorId heir) {
        lock.lock();
        try {
            requireOpen();
            if (pendingHeir != null || owner == null || !owner.equals(oldOwner)
                    || oldOwner.equals(heir) || retiredOwners.contains(heir)
                    || retiredOwners.size() >= MAX_RETIRED_OWNERS
                    || ownerEpoch == Long.MAX_VALUE)
                throw new IllegalStateException("ETS owner/heir generation cannot be prepared");
            pendingHeir = new HeirReceipt(
                    UUID.randomUUID(), oldOwner, heir, ownerEpoch + 1);
            return pendingHeir;
        } finally { lock.unlock(); }
    }

    /** Pending table is fenced, including PUBLIC readers and writers. */
    boolean retainForPendingHeir(ActorRuntime.ActorId deceasedOwner) {
        lock.lock();
        try {
            return !closed.get() && pendingHeir != null
                    && deceasedOwner.equals(owner)
                    && deceasedOwner.equals(pendingHeir.oldOwner());
        } finally { lock.unlock(); }
    }

    boolean acknowledgeHeir(HeirReceipt receipt) {
        lock.lock();
        try {
            if (closed.get() || !Objects.equals(pendingHeir, receipt))
                return false;
            retiredOwners.add(owner);
            owner = receipt.newOwner();
            ownerEpoch = receipt.nextEpoch();
            pendingHeir = null;
            return true;
        } finally { lock.unlock(); }
    }

    boolean rollbackHeir(HeirReceipt receipt) {
        lock.lock();
        try {
            if (closed.get() || !Objects.equals(pendingHeir, receipt))
                return false;
            pendingHeir = null; // only ActorRuntime may prove original owner live
            return true;
        } finally { lock.unlock(); }
    }

    boolean pendingHeirMatches(HeirReceipt receipt) {
        lock.lock();
        try { return !closed.get() && Objects.equals(pendingHeir, receipt); }
        finally { lock.unlock(); }
    }

    boolean belongsTo(ActorRuntime candidate) { return runtime == candidate; }

    public boolean closed() { return closed.get(); }

    public int size() {
        requireRead("read ETS size");
        lock.lock();
        try {
            requireRead("read ETS size");
            requireOpen();
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<V> get(K key) {
        requireRead("read ETS table");
        ActorRuntime.FrozenActorGroupEventPayload frozenKey = freeze(key);
        try {
            lock.lock();
            try {
                requireRead("read ETS table");
                requireOpen();
                StoredEntry entry = entries.get(frozenKey.value());
                return entry == null
                        ? Optional.empty()
                        : Optional.of((V) runtime.materializeEtsValue(entry.value.value()));
            } finally {
                lock.unlock();
            }
        } finally {
            frozenKey.reservation().close();
        }
    }

    /**
     * Insert or replace one key atomically. Returns true when a new key was
     * inserted and false when an existing key was replaced.
     */
    public boolean put(K key, V value) {
        requireWrite("write ETS table");
        ActorRuntime.FrozenActorGroupEventPayload frozenKey = freeze(key);
        ActorRuntime.FrozenActorGroupEventPayload frozenValue;
        try {
            frozenValue = freeze(value);
        } catch (Throwable failure) {
            frozenKey.reservation().close();
            throw failure;
        }

        lock.lock();
        try {
            requireWrite("write ETS table");
            requireOpen();
            StoredEntry existing = entries.get(frozenKey.value());
            if (existing != null) {
                frozenKey.reservation().close();
                ActorRuntime.FrozenActorGroupEventPayload oldValue = existing.value;
                existing.value = frozenValue;
                oldValue.reservation().close();
                return false;
            }
            if (entries.size() >= capacity) {
                throw new IllegalStateException(
                        "ETS table capacity exceeded: maximum " + capacity);
            }
            entries.put(frozenKey.value(), new StoredEntry(frozenKey, frozenValue));
            return true;
        } catch (Throwable failure) {
            // If ownership transferred into entries, do not double release.
            if (!entries.containsKey(frozenKey.value())) {
                safeClose(frozenKey);
                safeClose(frozenValue);
            }
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomic insert-if-absent. Returns true only for the winning insertion.
     */
    public boolean putIfAbsent(K key, V value) {
        requireWrite("insert into ETS table");
        ActorRuntime.FrozenActorGroupEventPayload frozenKey = freeze(key);
        ActorRuntime.FrozenActorGroupEventPayload frozenValue;
        try {
            frozenValue = freeze(value);
        } catch (Throwable failure) {
            frozenKey.reservation().close();
            throw failure;
        }

        lock.lock();
        try {
            requireWrite("insert into ETS table");
            requireOpen();
            if (entries.containsKey(frozenKey.value())) {
                frozenKey.reservation().close();
                frozenValue.reservation().close();
                return false;
            }
            if (entries.size() >= capacity) {
                frozenKey.reservation().close();
                frozenValue.reservation().close();
                throw new IllegalStateException(
                        "ETS table capacity exceeded: maximum " + capacity);
            }
            entries.put(frozenKey.value(), new StoredEntry(frozenKey, frozenValue));
            return true;
        } catch (Throwable failure) {
            if (!entries.containsKey(frozenKey.value())) {
                safeClose(frozenKey);
                safeClose(frozenValue);
            }
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(K key) {
        requireWrite("remove from ETS table");
        ActorRuntime.FrozenActorGroupEventPayload frozenKey = freeze(key);
        try {
            lock.lock();
            try {
                requireWrite("remove from ETS table");
                requireOpen();
                StoredEntry removed = entries.remove(frozenKey.value());
                if (removed == null) return false;
                removed.close();
                return true;
            } finally {
                lock.unlock();
            }
        } finally {
            frozenKey.reservation().close();
        }
    }

    /**
     * Atomic value CAS using Oreslang frozen-value equality.
     */
    public boolean compareAndSet(K key, V expected, V replacement) {
        requireWrite("compare-and-set ETS table");
        ActorRuntime.FrozenActorGroupEventPayload frozenKey = freeze(key);
        ActorRuntime.FrozenActorGroupEventPayload frozenExpected;
        ActorRuntime.FrozenActorGroupEventPayload frozenReplacement;
        try {
            frozenExpected = freeze(expected);
            try {
                frozenReplacement = freeze(replacement);
            } catch (Throwable failure) {
                frozenExpected.reservation().close();
                throw failure;
            }
        } catch (Throwable failure) {
            frozenKey.reservation().close();
            throw failure;
        }

        try {
            lock.lock();
            try {
                requireWrite("compare-and-set ETS table");
                requireOpen();
                StoredEntry entry = entries.get(frozenKey.value());
                if (entry == null || !Objects.equals(
                        entry.value.value(), frozenExpected.value())) {
                    return false;
                }
                ActorRuntime.FrozenActorGroupEventPayload old = entry.value;
                entry.value = frozenReplacement;
                frozenReplacement = null;
                old.reservation().close();
                return true;
            } finally {
                lock.unlock();
            }
        } finally {
            frozenKey.reservation().close();
            frozenExpected.reservation().close();
            if (frozenReplacement != null) frozenReplacement.reservation().close();
        }
    }

    /**
     * Bounded point-in-time snapshot. Source-level iteration/query lowering may
     * choose stricter cursor semantics later, but must never silently allocate
     * beyond this table's explicit capacity.
     */
    @SuppressWarnings("unchecked")
    public Map<K, V> snapshot() {
        requireRead("snapshot ETS table");
        lock.lock();
        try {
            requireRead("snapshot ETS table");
            requireOpen();
            LinkedHashMap<K, V> copy = new LinkedHashMap<>(entries.size());
            for (StoredEntry entry : entries.values()) {
                copy.put(
                        (K) runtime.materializeEtsValue(entry.key.value()),
                        (V) runtime.materializeEtsValue(entry.value.value()));
            }
            return Map.copyOf(copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            requireOwner("close ETS table");
            if (closed.get()) return;
            closed.set(true);
            pendingHeir = null;
            for (StoredEntry entry : entries.values()) entry.close();
            entries.clear();
        } finally { lock.unlock(); }
        runtime.unregisterEtsTable(this);
    }

    void closeFromRuntime() {
        lock.lock();
        try {
            if (!closed.compareAndSet(false, true)) return;
            pendingHeir = null;
            for (StoredEntry entry : entries.values()) entry.close();
            entries.clear();
        } finally {
            lock.unlock();
        }
        runtime.unregisterEtsTable(this);
    }

    private ActorRuntime.FrozenActorGroupEventPayload freeze(Object value) {
        Objects.requireNonNull(value, "ETS keys/values cannot be null; use Option<T>");
        return runtime.freezeActorGroupEventPayload(value);
    }

    private void requireRead(String operation) {
        requireRuntimeAffinity(operation);
        ActorRuntime.ActorId caller = ActorRuntime.currentActorId().orElse(null);
        if (caller == null) return; // trusted host/supervisor
        if (pendingHeir != null || retiredOwners.contains(caller))
            throw new SecurityException(operation + " denied by ETS transfer fence");
        if (access == Access.PRIVATE && !caller.equals(owner)) {
            throw new SecurityException(operation + " requires the ETS owner");
        }
    }

    private void requireWrite(String operation) {
        requireRuntimeAffinity(operation);
        ActorRuntime.ActorId caller = ActorRuntime.currentActorId().orElse(null);
        if (caller == null) return; // trusted host/supervisor
        if (pendingHeir != null || retiredOwners.contains(caller))
            throw new SecurityException(operation + " denied by ETS transfer fence");
        if (access != Access.PUBLIC && !caller.equals(owner)) {
            throw new SecurityException(operation + " requires the ETS owner");
        }
    }

    private void requireOwner(String operation) {
        requireRuntimeAffinity(operation);
        ActorRuntime.ActorId caller = ActorRuntime.currentActorId().orElse(null);
        if (caller == null) return;
        if (pendingHeir != null || retiredOwners.contains(caller))
            throw new SecurityException(operation + " denied by ETS transfer fence");
        if (!caller.equals(owner)) {
            throw new SecurityException(operation + " requires the ETS owner");
        }
    }

    private void requireRuntimeAffinity(String operation) {
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        if (current != null && current != runtime) {
            throw new SecurityException(operation + " cannot cross ActorRuntime boundaries");
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("ETS table is closed");
    }

    private static void safeClose(ActorRuntime.FrozenActorGroupEventPayload payload) {
        if (payload != null) payload.reservation().close();
    }
}
