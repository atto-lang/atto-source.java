package dev.oreslang.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted-host, ActorRuntime-bound ETS table authority.
 *
 * <p>This is a supervisor service, NOT a guest intrinsic or an actor-carrier
 * access path. ActorRef is issued by ActorRuntime; a table ID cannot be
 * reconstructed from an actor ID, numeric slot, String or serialized value.
 * A real guest API must authenticate the active logical Task at the runtime
 * boundary and dispatch storage operations off carriers before exposure.</p>
 *
 * <p>Access is restricted to actors on one ActorRuntime. This is a runtime
 * scope, not yet a verified tenant/isolate boundary. All table data remains
 * owned by the trusted host reference, not by an actor's private heap.</p>
 */
public final class OresEtsActorRegistry implements AutoCloseable {
    public enum Access { PRIVATE, PROTECTED, PUBLIC }

    /** Identity-only, non-serializable, per-registry capability. */
    public static final class TableId<K, V> {
        private final OresEtsActorRegistry issuer;
        private TableId(OresEtsActorRegistry issuer) { this.issuer = issuer; }
    }

    private static final class Entry {
        final ActorRuntime.ActorRef<?> owner;
        final Access access;
        final OresEtsTable<Object, Object> table;
        final AtomicBoolean retired = new AtomicBoolean();

        Entry(ActorRuntime.ActorRef<?> owner, Access access, int maxEntries, long maxBytes) {
            this.owner = owner;
            this.access = access;
            this.table = new OresEtsTable<>(maxEntries, maxBytes);
        }
    }

    private final ActorRuntime runtime;
    private final int maxTables;
    private final Object admissionLock = new Object();
    private final Map<TableId<?, ?>, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private final AtomicBoolean closed = new AtomicBoolean();

    public OresEtsActorRegistry(ActorRuntime runtime, int maxTables) {
        requireHost();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (maxTables < 1 || maxTables > 4096) {
            throw new IllegalArgumentException("maxTables must be 1..4096");
        }
        this.maxTables = maxTables;
        // One bounded supervisor cleanup lane per registry. A completion
        // callback must NEVER park an actor carrier or clear a CHM inline.
        reaper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("ores-ets-reaper").factory());
        reaper.scheduleWithFixedDelay(this::safeReap, 100, 100, TimeUnit.MILLISECONDS);
    }

    private static void requireHost() {
        if (ActorRuntime.isActorCarrierThread()
                || ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException("ETS host reference cannot run on actor/control carriers");
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("ETS registry is closed");
    }

    private void requireActor(ActorRuntime.ActorRef<?> ref) {
        Objects.requireNonNull(ref, "actor");
        // Never accept caller-authored ActorId, ActorAddress or a raw tenant
        // name as evidence of principal identity.
        if (!runtime.isAlive(ref)) {
            throw new SecurityException("actor is foreign, stopped, or not live");
        }
        if (ref.kind() == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException("ETS host service is unavailable to untrusted actors");
        }
    }

    public <K, V> TableId<K, V> create(
            ActorRuntime.ActorRef<?> owner, Access access, int maxEntries, long maxBytes) {
        requireHost();
        requireActor(owner);
        Objects.requireNonNull(access, "access");
        synchronized (admissionLock) {
            requireOpen();
            reapLocked();
            if (entries.size() >= maxTables) {
                throw new IllegalStateException("ETS table count quota exceeded");
            }
            // Revalidate under admission lock; actor STOP can race admission.
            requireActor(owner);
            TableId<K, V> id = new TableId<>(this);
            Entry entry = new Entry(owner, access, maxEntries, maxBytes);
            entries.put(id, entry);
            // The done callback runs on a completion thread. It may be a
            // carrier, so its ONLY responsibility is atomic revocation.
            owner.done().whenCompleteRuntime((value, failure) ->
                    entry.retired.set(true));
            if (!owner.isAlive()) entry.retired.set(true);
            return id;
        }
    }

    private Entry authorize(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller, boolean write) {
        requireHost();
        Objects.requireNonNull(id, "table ID");
        if (id.issuer != this) throw new SecurityException("foreign ETS table capability");
        requireOpen();
        requireActor(caller);
        Entry entry = entries.get(id);
        if (entry == null || entry.retired.get() || !entry.owner.isAlive()) {
            if (entry != null) entry.retired.set(true);
            throw new IllegalStateException("stale or retired ETS table capability");
        }
        boolean owner = caller == entry.owner;
        if ((!owner && entry.access == Access.PRIVATE)
                || (!owner && write && entry.access != Access.PUBLIC)) {
            throw new SecurityException("ETS table access denied");
        }
        return entry;
    }

    /** Owner-only retirement, with physical cleanup on a host thread. */
    public void retire(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        Entry entry = authorize(id, caller, true);
        if (entry.owner != caller) throw new SecurityException("only the owner may retire ETS table");
        entry.retired.set(true);
        reap();
    }

    public <K, V> Optional<V> lookup(TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key) {
        Entry e = authorize(id, caller, false);
        @SuppressWarnings("unchecked") Optional<V> result = (Optional<V>) e.table.lookup(key);
        return result;
    }

    public <K, V> Optional<V> insert(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V value) {
        Entry e = authorize(id, caller, true);
        @SuppressWarnings("unchecked") Optional<V> result = (Optional<V>) e.table.insert(key, value);
        return result;
    }

    public <K, V> boolean insertNew(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V value) {
        return authorize(id, caller, true).table.insertNew(key, value);
    }

    public <K, V> boolean compareExchange(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V expected, V replacement) {
        return authorize(id, caller, true).table.compareExchange(key, expected, replacement);
    }

    public <K, V> Optional<V> take(TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key) {
        Entry e = authorize(id, caller, true);
        @SuppressWarnings("unchecked") Optional<V> result = (Optional<V>) e.table.take(key);
        return result;
    }

    public int size(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        return authorize(id, caller, false).table.size();
    }

    public long byteSize(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        return authorize(id, caller, false).table.byteSize();
    }

    public <K, V> Map<K, V> snapshot(TableId<K, V> id, ActorRuntime.ActorRef<?> caller) {
        Entry e = authorize(id, caller, false);
        @SuppressWarnings("unchecked") Map<K, V> result = (Map<K, V>) e.table.snapshot();
        return result;
    }

    /** Synchronous supervisor sweep, never callable from an actor carrier. */
    public int reap() {
        requireHost();
        synchronized (admissionLock) {
            return reapLocked();
        }
    }

    private void safeReap() {
        try {
            synchronized (admissionLock) {
                reapLocked();
            }
        } catch (Throwable failure) {
            // Never abort a scheduler cleanup lane on an ordinary failure.
            if (failure instanceof VirtualMachineError vme) throw vme;
            if (failure instanceof ThreadDeath death) throw death;
        }
    }

    private int reapLocked() {
        int removed = 0;
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (!closed.get() && !entry.retired.get() && entry.owner.isAlive()) continue;
            entry.retired.set(true);
            // Remove before invoking host cleanup; all later accesses fail.
            if (entries.remove(item.getKey(), entry)) {
                entry.table.close();
                removed++;
            }
        }
        return removed;
    }

    public int liveTableCount() {
        requireHost();
        synchronized (admissionLock) {
            reapLocked();
            return entries.size();
        }
    }

    @Override
    public void close() {
        requireHost();
        if (!closed.compareAndSet(false, true)) return;
        reaper.shutdown();
        synchronized (admissionLock) {
            reapLocked();
        }
    }
}
