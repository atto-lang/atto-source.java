package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Trusted host substrate for an actor-owned, fixed-size homogeneous ECS shard.
 *
 * <p>One physical layout is authoritative: AoS, SoA, or AoSoA. Allocation
 * and every operation require the same logical actor turn, not the same OS
 * carrier thread. Values never cross actor boundaries as mutable references:
 * snapshotColumn returns a copy. No guest lowering or scoped Task permit is
 * implied by this host-only type.</p>
 */
public final class ActorShardColumns implements AutoCloseable {
    public enum Layout { AOS, SOA, AOSOA }
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final ActorRuntime.ActorId owner;
    private final Layout layout;
    private final int rows;
    private final int fields;
    private final int lanes;
    private final double[] storage;
    private final ActorRuntime.ActorShardLease reservation;

    /**
     * Construct only inside a scheduled actor turn. AoSoA must use a power of
     * two lane count; AoS and SoA use lanes=1.
     */
    public ActorShardColumns(Layout layout, int rows, int fields, int lanes) {
        this.owner = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("shard creation requires an executing actor"));
        this.layout = Objects.requireNonNull(layout, "layout");
        if (rows <= 0 || fields <= 0) {
            throw new IllegalArgumentException("shard dimensions must be positive");
        }
        if (lanes <= 0 || lanes > 1024 || (lanes & (lanes - 1)) != 0
                || (layout != Layout.AOSOA && lanes != 1)) {
            throw new IllegalArgumentException("invalid shard lane count");
        }
        this.rows = rows;
        this.fields = fields;
        this.lanes = lanes;
        long physicalRows = layout == Layout.AOSOA
                ? ((long) rows + lanes - 1L) / lanes * lanes
                : rows;
        long elements = Math.multiplyExact(physicalRows, (long) fields);
        if (elements > MAX_BYTES / Double.BYTES) {
            throw new IllegalArgumentException("shard exceeds 16 MiB host allocation cap");
        }
        int bytes = Math.toIntExact(elements * Double.BYTES);
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        if (runtime == null) {
            throw new SecurityException("shard allocation requires an owning runtime");
        }
        ActorRuntime.ActorShardLease allocated = runtime.reserveActorShard(bytes);
        double[] heap = null;
        try {
            if (!allocated.privateBacking()) heap = new double[(int) elements];
        } catch (RuntimeException | Error failure) {
            allocated.close();
            throw failure;
        }
        this.reservation = allocated;
        this.storage = heap;
    }

    boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor) {
        return reservation.ownedBy(runtime, actor);
    }
    boolean isPrivateBacked() { return reservation.privateBacking(); }

    public Layout layout() { return layout; }
    public int rows() { return rows; }
    public int fields() { return fields; }
    public int lanes() { return lanes; }

    private void requireOwner() {
        reservation.requireOwner();
    }

    private double get(int index) {
        return reservation.privateBacking() ? reservation.read(index) : storage[index];
    }

    private void put(int index, double value) {
        if (reservation.privateBacking()) reservation.write(index, value);
        else storage[index] = value;
    }

    @Override public void close() {
        reservation.close(); // owner-checked and idempotent; reads remain revoked
    }

    private int offset(int row, int field) {
        if (row < 0 || row >= rows || field < 0 || field >= fields) {
            throw new IndexOutOfBoundsException("shard row/field outside bounds");
        }
        return switch (layout) {
            case AOS -> row * fields + field;
            case SOA -> field * rows + row;
            case AOSOA -> ((row / lanes) * fields + field) * lanes + row % lanes;
        };
    }

    public double read(int row, int field) {
        requireOwner();
        return get(offset(row, field));
    }

    public void write(int row, int field, double value) {
        requireOwner();
        put(offset(row, field), value);
    }

    /**
     * Dense column update, one physical actor turn for arbitrarily many
     * logical entities. Field columns may be distinct; no per-row actors or
     * Futures are created. This is not yet a parallel borrowed Task kernel.
     */
    public void integrate(int position, int velocity, double dt) {
        requireOwner();
        if (position == velocity || position < 0 || velocity < 0
                || position >= fields || velocity >= fields) {
            throw new IllegalArgumentException("requires distinct valid position/velocity fields");
        }
        if (!Double.isFinite(dt)) throw new IllegalArgumentException("nonfinite dt");
        for (int row = 0; row < rows; row++) {
            int p = offset(row, position);
            put(p, get(p) + get(offset(row, velocity)) * dt);
        }
    }

    /** Caller receives a detached immutable-by-ownership snapshot copy. */
    public double[] snapshotColumn(int field) {
        requireOwner();
        if (field < 0 || field >= fields) {
            throw new IndexOutOfBoundsException("invalid shard field");
        }
        double[] snapshot = new double[rows];
        for (int row = 0; row < rows; row++) snapshot[row] = get(offset(row, field));
        return snapshot;
    }
}
