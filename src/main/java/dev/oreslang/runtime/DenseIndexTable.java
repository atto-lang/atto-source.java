package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Bounded identity and generation sidecar for compact 32-bit dense indices.
 *
 * <p>The hot representation is still a single unsigned 32-bit index+1 word.
 * This table owns the out-of-line incarnation metadata needed when an identity
 * survives row deletion/reuse, table clear or transfer between in-process
 * components. It is not an ActorRef and does not authorize access to
 * actor memory; actor confinement and borrow rules remain independently
 * required.</p>
 *
 * <p>All public operations are synchronized so a validation cannot race a
 * remove/reuse within this metadata object. Storage access still belongs to
 * the owning table/actor. A successful resolve is a momentary validation, not
 * a lease that permits use after the caller's ownership scope ends.</p>
 */
public final class DenseIndexTable<TTag> implements AutoCloseable {
    private static final int MAX_CAPACITY = 1_000_000;
    /** One byte of liveness, one long generation and one int free-list entry. */
    private static final int SIDECAR_BYTES_PER_SLOT = 1 + Long.BYTES + Integer.BYTES;

    private final Object identity = new Object();
    private final boolean[] live;
    private final long[] generations;
    private final int[] freeSlots;
    private final int capacity;
    private final ActorRuntime ownerRuntime;
    private final ActorRuntime.ActorId ownerActor;
    private final ActorRuntime.ActorShardLease sidecarLease;
    private int nextSlot;
    private int freeCount;
    private int liveCount;
    private long epoch = 1;
    private boolean closed;

    public DenseIndexTable(int capacity) {
        this(capacity, null, null, null);
    }

    private DenseIndexTable(
            int capacity,
            ActorRuntime ownerRuntime,
            ActorRuntime.ActorId ownerActor,
            ActorRuntime.ActorShardLease sidecarLease) {
        requireValidCapacity(capacity);
        this.capacity = capacity;
        this.ownerRuntime = ownerRuntime;
        this.ownerActor = ownerActor;
        this.sidecarLease = sidecarLease;
        this.live = new boolean[capacity];
        this.generations = new long[capacity];
        this.freeSlots = new int[capacity];
        Arrays.fill(generations, 1L);
    }

    private static void requireValidCapacity(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("dense index table capacity outside bounded range");
        }
    }

    /**
     * Create a table permanently confined to the currently executing actor.
     *
     * <p>The sidecar arrays are charged to the actor heap limit before they are
     * allocated, so identity metadata cannot escape per-actor accounting. The
     * charge is refunded by {@link #close()} or by actor finalization.</p>
     */
    public static <TTag> DenseIndexTable<TTag> forCurrentActor(int capacity) {
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        ActorRuntime.ActorId actor = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("actor-bound dense table requires an executing actor"));
        if (runtime == null) {
            throw new SecurityException("actor-bound dense table requires an actor runtime");
        }
        requireValidCapacity(capacity);
        ActorRuntime.ActorShardLease lease =
                runtime.reserveActorShard(capacity * SIDECAR_BYTES_PER_SLOT);
        try {
            return new DenseIndexTable<>(capacity, runtime, actor, lease);
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor) {
        return ownerRuntime != null && ownerActor != null
                && ownerRuntime == runtime && ownerActor.equals(actor);
    }

    private void requireOwner() {
        if (ownerRuntime != null
                && (ActorRuntime.currentActorRuntime() != ownerRuntime
                    || !ActorRuntime.currentActorId().filter(ownerActor::equals).isPresent())) {
            throw new SecurityException("dense index table accessed outside its owning actor");
        }
    }

    /** Allocate the next available row identity. */
    public synchronized DenseIndex32<TTag> allocate() {
        requireOwner();
        ensureOpen();
        final int slot;
        if (freeCount != 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (nextSlot == capacity) {
                throw new IllegalStateException("dense index table capacity exhausted");
            }
            slot = nextSlot++;
        }
        if (live[slot] || generations[slot] <= 0) {
            throw new IllegalStateException("dense index table free-list corruption");
        }
        live[slot] = true;
        liveCount++;
        return new DenseIndex32<>(identity, epoch,
                ManagedReferencePolicy.encodeDenseIndex32(slot), generations[slot]);
    }

    /** Validate exact table identity, current epoch, slot and generation. */
    public synchronized boolean isLive(DenseIndex32<TTag> handle) {
        requireOwner();
        if (closed || handle == null || !handle.belongsTo(identity)
                || handle.tableEpoch() != epoch) {
            return false;
        }
        final long logicalIndex = handle.index();
        if (logicalIndex < 0 || logicalIndex >= nextSlot) return false;
        int slot = (int) logicalIndex;
        return live[slot] && generations[slot] == handle.generation();
    }

    /** Return the compact word only after validating its out-of-line identity. */
    public synchronized OptionalInt checkedStorageWord(DenseIndex32<TTag> handle) {
        requireOwner();
        return isLive(handle) ? OptionalInt.of(handle.encodedWord()) : OptionalInt.empty();
    }

    /**
     * Retire one identity. A generation at the terminal value is permanently
     * retired rather than wrapped and made capable of reviving an old handle.
     */
    public synchronized boolean remove(DenseIndex32<TTag> handle) {
        requireOwner();
        if (!isLive(handle)) return false;
        int slot = (int) handle.index();
        live[slot] = false;
        liveCount--;
        if (generations[slot] != Long.MAX_VALUE) {
            generations[slot]++;
            freeSlots[freeCount++] = slot;
        }
        return true;
    }

    /**
     * Invalidate all outstanding handles with one epoch transition.
     * Epoch exhaustion permanently closes the table instead of wrapping.
     */
    public synchronized void clear() {
        requireOwner();
        ensureOpen();
        if (epoch == Long.MAX_VALUE) {
            closeInternal();
            throw new IllegalStateException("dense index table epoch exhausted; table retired");
        }
        epoch++;
        Arrays.fill(live, false);
        Arrays.fill(generations, 1L);
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
    }

    public synchronized int size() {
        requireOwner();
        return liveCount;
    }

    public int capacity() {
        return capacity;
    }

    public synchronized long epoch() {
        requireOwner();
        ensureOpen();
        return epoch;
    }

    @Override
    public synchronized void close() {
        requireOwner();
        if (closed) return;
        closeInternal();
    }

    private void closeInternal() {
        closed = true;
        Arrays.fill(live, false);
        Arrays.fill(generations, 0L);
        Arrays.fill(freeSlots, 0);
        nextSlot = 0;
        freeCount = 0;
        liveCount = 0;
        if (sidecarLease != null) sidecarLease.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("dense index table is closed");
    }
}
