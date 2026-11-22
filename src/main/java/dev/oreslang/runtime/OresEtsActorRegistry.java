package dev.oreslang.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
        private static final long RETIRED = Long.MIN_VALUE;
        private static final long COUNT = Long.MAX_VALUE;
        /** Do not admit unlimited overlapping host operations per table. */
        private static final int MAX_INFLIGHT_OPERATIONS = 256;
        final AtomicLong admission = new AtomicLong();
        void pin() {
            for (;;) {
                long old = admission.get();
                if ((old & RETIRED) != 0) throw new IllegalStateException("ETS table retired");
                if ((old & COUNT) >= MAX_INFLIGHT_OPERATIONS) {
                    throw new IllegalStateException("ETS in-flight operation quota exceeded");
                }
                if (admission.compareAndSet(old, old + 1)) return;
            }
        }
        boolean unpin() {
            for (;;) {
                long old = admission.get();
                long count = old & COUNT;
                // Never mutate the sign-bit retirement fence on underflow.
                if (count == 0) throw new AssertionError("ETS pin underflow");
                long next = (old & RETIRED) | (count - 1);
                if (admission.compareAndSet(old, next)) {
                    return (old & RETIRED) != 0 && count == 1;
                }
            }
        }
        void retire() { admission.getAndUpdate(old -> old | RETIRED); }
        boolean retired() { return (admission.get() & RETIRED) != 0; }
        boolean quiescent() { return (admission.get() & COUNT) == 0; }

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
    /** Bounds active host registries and their periodic reaper tasks. */
    private static final int MAX_HOST_REGISTRIES = 1024;
    private static final AtomicInteger ACTIVE_HOST_REGISTRIES = new AtomicInteger();

    private static void reserveRegistry() {
        for (;;) {
            int active = ACTIVE_HOST_REGISTRIES.get();
            if (active >= MAX_HOST_REGISTRIES) {
                throw new IllegalStateException("ETS host registry quota exceeded");
            }
            if (ACTIVE_HOST_REGISTRIES.compareAndSet(active, active + 1)) return;
        }
    }

    /**
     * Shared trusted-host cleanup lane. Cancellation removes registry tasks
     * promptly; actor/CONTROL carriers never execute map cleanup here.
     */
    private static final ScheduledThreadPoolExecutor HOST_REAPER =
            new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().daemon(true).name("ores-ets-host-reaper").factory());
    static { HOST_REAPER.setRemoveOnCancelPolicy(true); }
    private final ScheduledFuture<?> reaperTask;
    private final AtomicBoolean closed = new AtomicBoolean();

    public OresEtsActorRegistry(ActorRuntime runtime, int maxTables) {
        requireHost();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (maxTables < 1 || maxTables > 4096) {
            throw new IllegalArgumentException("maxTables must be 1..4096");
        }
        this.maxTables = maxTables;
        // One shared cleanup carrier for all trusted host registries.
        // Actor termination is observed by liveness and supervisor sweeps;
        // no per-table completion callback runs on a carrier.
        reserveRegistry();
        try {
            reaperTask = HOST_REAPER.scheduleWithFixedDelay(
                    this::safeReap, 100, 100, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error failure) {
            ACTIVE_HOST_REGISTRIES.decrementAndGet();
            throw failure;
        }
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
            // Do not subscribe one callback per table to owner.done().
            // Retired tables can otherwise pin their Entry until the actor
            // eventually terminates, and bulk table churn consumes Future
            // waiter slots. Every access rechecks ActorRuntime liveness;
            // the shared host reaper observes STOP/cancellation and revokes
            // all owner tables without invoking callbacks on actor carriers.
            if (!owner.isAlive()) entry.retire();
            return id;
        }
    }

    /**
     * An operation obtains a CAS-backed lease on the same state word that
     * marks table retirement. This closes authorization-to-storage TOCTOU
     * against explicit table retire. ActorRuntime STOP remains independent:
     * operations admitted BEFORE STOP may finish AFTER STOP; strict commit
     * fencing needs a shared ActorRuntime admission gate, not yet installed.
     */
    private final class Pin implements AutoCloseable {
        private final Entry entry;
        private boolean released;
        Pin(Entry entry) { this.entry = entry; }
        Entry entry() { return entry; }
        @Override public void close() {
            if (released) throw new AssertionError("ETS pin released twice");
            released = true;
            if (entry.unpin()) reap();
        }
    }

    private Pin authorize(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller, boolean write) {
        requireHost();
        Objects.requireNonNull(id, "table ID");
        if (id.issuer != this) throw new SecurityException("foreign ETS table capability");
        requireOpen();
        requireActor(caller);
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalStateException("stale ETS table capability");
        boolean owner = caller == entry.owner;
        if ((!owner && entry.access == Access.PRIVATE)
                || (!owner && write && entry.access != Access.PUBLIC))
            throw new SecurityException("ETS table access denied");
        entry.pin();
        if (closed.get() || !runtime.isAlive(caller) || !runtime.isAlive(entry.owner)) {
            entry.retire();
            if (entry.unpin()) reap();
            throw new IllegalStateException("ETS table or actor retired during admission");
        }
        return new Pin(entry);
    }

    /** Owner-only retirement, physical release after the last admitted pin. */
    public void retire(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        try (var pin = authorize(id, caller, true)) {
            if (pin.entry().owner != caller)
                throw new SecurityException("only the owner may retire ETS table");
            pin.entry().retire();
        }
        reap();
    }

    private <R> R access(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller,
                         boolean write, Function<OresEtsTable<Object, Object>, R> op) {
        try (var pin = authorize(id, caller, write)) {
            return op.apply(pin.entry().table);
        }
    }

    public <K, V> Optional<V> lookup(TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key) {
        @SuppressWarnings("unchecked") Optional<V> result =
                (Optional<V>) access(id, caller, false, table -> table.lookup(key));
        return result;
    }

    public <K, V> Optional<V> insert(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V value) {
        @SuppressWarnings("unchecked") Optional<V> result =
                (Optional<V>) access(id, caller, true, table -> table.insert(key, value));
        return result;
    }

    public <K, V> boolean insertNew(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V value) {
        return access(id, caller, true, table -> table.insertNew(key, value));
    }

    public <K, V> boolean compareExchange(
            TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key, V expected, V replacement) {
        return access(id, caller, true,
                table -> table.compareExchange(key, expected, replacement));
    }

    public <K, V> Optional<V> take(TableId<K, V> id, ActorRuntime.ActorRef<?> caller, K key) {
        @SuppressWarnings("unchecked") Optional<V> result =
                (Optional<V>) access(id, caller, true, table -> table.take(key));
        return result;
    }

    public int size(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        return access(id, caller, false, OresEtsTable::size);
    }

    public long byteSize(TableId<?, ?> id, ActorRuntime.ActorRef<?> caller) {
        return access(id, caller, false, OresEtsTable::byteSize);
    }

    public <K, V> Map<K, V> snapshot(TableId<K, V> id, ActorRuntime.ActorRef<?> caller) {
        @SuppressWarnings("unchecked") Map<K, V> result =
                (Map<K, V>) access(id, caller, false, OresEtsTable::snapshot);
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
            if (!closed.get() && !entry.retired() && entry.owner.isAlive()) continue;
            entry.retire();
            // In-flight host operations hold a pin: no physical cleanup
            // until all operations admitted before retirement have exited.
            if (!entry.quiescent()) continue;
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
        reaperTask.cancel(false);
        try {
            synchronized (admissionLock) {
                reapLocked();
            }
        } finally {
            // Count the registry once, even if cleanup is deferred until a
            // previously admitted host operation releases its last pin.
            ACTIVE_HOST_REGISTRIES.decrementAndGet();
        }
    }
}
