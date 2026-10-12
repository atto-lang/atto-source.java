package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actor-owned, typed little-endian i64 storage with generational handles.
 *
 * This is the portable Java/Graal ABI prototype for compiler-lowered scalar
 * guest data. Storage is actor-confined direct memory, NOT a separate Java
 * heap/collector or an immediately freed native allocation. The matching
 * LLVM backend can use stack/native arenas with no tracing GC.
 */
public final class ActorOwnedI64Region implements AutoCloseable {
    public static final class Slot {
        private final Object identity;
        private final int index;
        private final long generation;
        private Slot(Object identity, int index, long generation) {
            this.identity = identity;
            this.index = index;
            this.generation = generation;
        }
        public int index() { return index; }
        public long generation() { return generation; }
    }

    private final Object identity = new Object();
    private final ActorRuntime.PrivateMemoryBlock memory;
    private final Runnable requireActor;
    private final Runnable onRetired;
    private final long[] generations;
    private final boolean[] occupied;
    private boolean closed;
    private int allocated;
    private final AtomicBoolean retired = new AtomicBoolean();

    ActorOwnedI64Region(ActorRuntime.PrivateMemoryBlock memory, int slots,
                        Runnable requireActor, Runnable onRetired) {
        if (slots < 1 || slots > Integer.MAX_VALUE / Long.BYTES) {
            throw new IllegalArgumentException("bounded i64 slot count required");
        }
        this.memory = Objects.requireNonNull(memory);
        this.requireActor = Objects.requireNonNull(requireActor);
        this.onRetired = Objects.requireNonNull(onRetired);
        if (memory.capacity() != slots * Long.BYTES) {
            throw new IllegalArgumentException("i64 region storage size mismatch");
        }
        this.generations = new long[slots];
        this.occupied = new boolean[slots];
        Arrays.fill(generations, 1);
    }

    private void checkOwner() { requireActor.run(); }

    public synchronized Slot allocate(long value) {
        checkOwner();
        requireOpen();
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i] && generations[i] != Long.MAX_VALUE) {
                memory.writeLong(i * Long.BYTES, value);
                occupied[i] = true;
                allocated++;
                return new Slot(identity, i, generations[i]);
            }
        }
        throw new IllegalStateException("actor i64 region capacity exhausted");
    }

    public synchronized long read(Slot slot) {
        checkOwner();
        int i = requireLive(slot);
        return memory.readLong(i * Long.BYTES);
    }

    public synchronized void write(Slot slot, long value) {
        checkOwner();
        int i = requireLive(slot);
        memory.writeLong(i * Long.BYTES, value);
    }

    /** Drops exactly one scalar slot and invalidates all existing handles. */
    public synchronized void drop(Slot slot) {
        checkOwner();
        int i = requireLive(slot);
        if (generations[i] == Long.MAX_VALUE) {
            throw new IllegalStateException("i64 slot generation exhausted");
        }
        memory.writeLong(i * Long.BYTES, 0L);
        generations[i]++;
        occupied[i] = false;
        allocated--;
    }

    public synchronized int size() { return allocated; }
    public synchronized int capacity() { return occupied.length; }
    public synchronized boolean closed() { return closed || memory.closed(); }

    @Override public void close() {
        checkOwner();
        retire(false);
    }

    /** Actor lifecycle uses this entrypoint without granting carrier authority. */
    void retireFromActor() { retire(true); }

    private synchronized void retire(boolean actorShutdown) {
        if (!retired.compareAndSet(false, true)) return;
        try {
            // No raw ByteBuffer escapes the owning block. Bulk invalidation is
            // handled by the enclosing slice when the actor is finalizing.
            if (!actorShutdown && !memory.closed()) memory.close();
        } finally {
            Arrays.fill(occupied, false);
            allocated = 0;
            closed = true;
            onRetired.run();
        }
    }

    private void requireOpen() {
        if (closed || memory.closed()) {
            throw new IllegalStateException("actor i64 region has retired");
        }
    }

    private int requireLive(Slot slot) {
        requireOpen();
        if (slot == null || slot.identity != identity || slot.index < 0
                || slot.index >= occupied.length || !occupied[slot.index]
                || generations[slot.index] != slot.generation) {
            throw new IllegalStateException("stale or foreign i64 slot");
        }
        return slot.index;
    }
}
