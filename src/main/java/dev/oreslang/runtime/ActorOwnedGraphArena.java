package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * An actor-owned, bounded graph with exactly one owner of each payload.
 *
 * Node links do not own their targets; cycles are allowed. Payloads are
 * deterministically closed at erase/arena retirement, independently of edge
 * cycles. Outstanding host leases postpone retirement until they close.
 *
 * This is a Java runtime/interop substrate, NOT a native zero-GC allocator.
 * The compiler must prove unique ownership and non-escaping borrows before
 * lowering Oreslang source objects onto this API.
 */
public final class ActorOwnedGraphArena<T extends AutoCloseable> implements AutoCloseable {
    public static final class NodeId {
        private final Object arena;
        private final int slot;
        private final long generation;

        private NodeId(Object arena, int slot, long generation) {
            this.arena = arena;
            this.slot = slot;
            this.generation = generation;
        }

        public int slot() { return slot; }
        public long generation() { return generation; }

        @Override public boolean equals(Object value) {
            return value instanceof NodeId that && arena == that.arena
                    && slot == that.slot && generation == that.generation;
        }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(arena) + slot)
                    + Long.hashCode(generation);
        }
    }

    public final class Lease implements AutoCloseable {
        private final NodeId id;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(NodeId id) { this.id = id; }

        public T get() {
            checkActor();
            synchronized (ActorOwnedGraphArena.this) {
                if (released.get() || retiring || !live(id)) {
                    throw new IllegalStateException("actor graph lease is stale or retired");
                }
                return payload(id.slot);
            }
        }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            List<T> cleanup = List.of();
            synchronized (ActorOwnedGraphArena.this) {
                int slot = id.slot;
                if (loans[slot] <= 0) {
                    throw new IllegalStateException("actor graph loan accounting underflow");
                }
                loans[slot]--;
                activeLoans--;
                if (retiring && activeLoans == 0) cleanup = detachAll();
            }
            runDrops(cleanup);
        }
    }

    private final Object identity = new Object();
    private final Runnable ownerGuard;
    private final Consumer<ActorOwnedGraphArena<T>> onRetired;
    private final Object[] values;
    private final long[] generations;
    private final int[] loans;
    private final List<NodeId>[] edges;
    private final IdentityHashMap<T, Boolean> uniquePayloads = new IdentityHashMap<>();
    private final int maxEdgesPerNode;
    private int activeLoans;
    private int size;
    private boolean retiring;
    private boolean fullyClosed;
    private volatile Throwable cleanupFailure;

    @SuppressWarnings("unchecked")
    ActorOwnedGraphArena(int capacity, int maxEdgesPerNode,
                         Runnable ownerGuard, Consumer<ActorOwnedGraphArena<T>> onRetired) {
        if (capacity < 1 || capacity > 65536 || maxEdgesPerNode < 1
                || (long) capacity * maxEdgesPerNode > 1_000_000L) {
            throw new IllegalArgumentException("bounded graph capacity and degree required");
        }
        this.ownerGuard = Objects.requireNonNull(ownerGuard);
        this.onRetired = Objects.requireNonNull(onRetired);
        this.values = new Object[capacity];
        this.generations = new long[capacity];
        this.loans = new int[capacity];
        this.edges = (List<NodeId>[]) new List<?>[capacity];
        for (int i = 0; i < capacity; i++) edges[i] = new ArrayList<>();
        Arrays.fill(generations, 1L);
        this.maxEdgesPerNode = maxEdgesPerNode;
    }

    /**
     * A minimum bounded metadata charge. Guest payload allocations themselves
     * still need compiler-provided, backend-specific accounting.
     */
    public static long estimatedMetadataBytes(int capacity, int degree) {
        if (capacity < 1 || capacity > 65536 || degree < 1
                || (long) capacity * degree > 1_000_000L) {
            throw new IllegalArgumentException("bounded graph capacity and degree required");
        }
        return Math.addExact(128L, Math.addExact(
                Math.multiplyExact(64L, capacity),
                Math.multiplyExact(32L, (long) capacity * degree)));
    }

    private void checkActor() { ownerGuard.run(); }

    public NodeId add(Supplier<? extends T> factory) {
        checkActor();
        Objects.requireNonNull(factory, "factory");
        synchronized (this) {
            requireOpen();
            int slot = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null && generations[i] != Long.MAX_VALUE) {
                    slot = i;
                    break;
                }
            }
            if (slot == -1) throw new IllegalStateException("actor graph capacity exhausted");
            T value = Objects.requireNonNull(factory.get(), "node payload");
            if (uniquePayloads.containsKey(value)) {
                throw new IllegalStateException("same node payload cannot be owned twice");
            }
            values[slot] = value;
            uniquePayloads.put(value, Boolean.TRUE);
            edges[slot].clear();
            size++;
            return new NodeId(identity, slot, generations[slot]);
        }
    }

    public synchronized Lease borrow(NodeId id) {
        checkActor();
        requireLive(id);
        loans[id.slot]++;
        activeLoans++;
        return new Lease(id);
    }

    public synchronized void link(NodeId from, NodeId to) {
        checkActor();
        requireLive(from);
        requireLive(to);
        List<NodeId> out = edges[from.slot];
        if (out.contains(to)) return;
        out.removeIf(next -> !live(next));
        if (out.size() == maxEdgesPerNode)
            throw new IllegalStateException("actor graph degree limit");
        out.add(to);
    }

    public synchronized List<NodeId> links(NodeId from) {
        checkActor();
        requireLive(from);
        List<NodeId> out = edges[from.slot];
        out.removeIf(next -> !live(next));
        return List.copyOf(out);
    }

    public void erase(NodeId id) {
        checkActor();
        T drop;
        synchronized (this) {
            requireLive(id);
            if (loans[id.slot] != 0)
                throw new IllegalStateException("cannot drop borrowed graph node");
            if (generations[id.slot] == Long.MAX_VALUE)
                throw new IllegalStateException("graph generation exhausted");
            drop = payload(id.slot);
            values[id.slot] = null;
            uniquePayloads.remove(drop);
            generations[id.slot]++;
            edges[id.slot].clear();
            size--;
        }
        runDrops(List.of(drop));
    }

    public synchronized int size() { return size; }
    public synchronized int activeLoans() { return activeLoans; }
    public synchronized boolean retired() { return retiring; }
    public synchronized boolean fullyClosed() { return fullyClosed; }
    public Optional<Throwable> cleanupFailure() {
        return Optional.ofNullable(cleanupFailure);
    }

    @Override public void close() {
        checkActor();
        retireFromActor();
    }

    /** Called only by the owning actor memory slice after actor finalization. */
    void retireFromActor() {
        List<T> cleanup = List.of();
        synchronized (this) {
            if (retiring) return;
            retiring = true;
            if (activeLoans == 0) cleanup = detachAll();
        }
        runDrops(cleanup);
    }

    private List<T> detachAll() {
        List<T> cleanup = new ArrayList<>(size);
        for (int i = values.length - 1; i >= 0; i--) {
            if (values[i] != null) {
                cleanup.add(payload(i));
                values[i] = null;
                edges[i].clear();
            }
        }
        uniquePayloads.clear();
        size = 0;
        fullyClosed = true;
        // A broken host reservation callback may not skip detached payload drops.
        try {
            onRetired.accept(this);
        } catch (Throwable failure) {
            recordCleanupFailure(failure);
        }
        return cleanup;
    }

    private void runDrops(List<T> cleanup) {
        // Attempt all drops, preserving VM-fatal precedence after sibling cleanup.
        for (T value : cleanup) {
            try {
                value.close();
            } catch (Throwable failure) {
                recordCleanupFailure(failure);
            }
        }
        Throwable failure = cleanupFailure;
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    private synchronized void recordCleanupFailure(Throwable failure) {
        if (cleanupFailure == null) {
            cleanupFailure = failure;
        } else if ((failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
                && !(cleanupFailure instanceof VirtualMachineError)
                && !(cleanupFailure instanceof ThreadDeath)) {
            failure.addSuppressed(cleanupFailure);
            cleanupFailure = failure;
        } else if (cleanupFailure != failure) {
            cleanupFailure.addSuppressed(failure);
        }
    }

    private void requireOpen() {
        if (retiring) throw new IllegalStateException("actor graph arena retired");
    }

    private boolean live(NodeId id) {
        return !retiring && id != null && id.arena == identity
                && id.slot >= 0 && id.slot < values.length
                && values[id.slot] != null && generations[id.slot] == id.generation;
    }

    private void requireLive(NodeId id) {
        requireOpen();
        if (!live(id)) throw new IllegalStateException("foreign or stale actor graph node");
    }

    @SuppressWarnings("unchecked")
    private T payload(int slot) { return (T) values[slot]; }
}
