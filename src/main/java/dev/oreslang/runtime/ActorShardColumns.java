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
public final class ActorShardColumns {
    public enum Layout { AOS, SOA, AOSOA }
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final ActorRuntime.ActorId owner;
    private final Layout layout;
    private final int rows;
    private final int fields;
    private final int lanes;
    private final double[] storage;

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
        this.storage = new double[(int) elements];
    }

    public Layout layout() { return layout; }
    public int rows() { return rows; }
    public int fields() { return fields; }
    public int lanes() { return lanes; }

    private void requireOwner() {
        if (!ActorRuntime.currentActorId().filter(owner::equals).isPresent()) {
            throw new SecurityException("shard data is accessible only in its owning actor turn");
        }
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
        return storage[offset(row, field)];
    }

    public void write(int row, int field, double value) {
        requireOwner();
        storage[offset(row, field)] = value;
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
            storage[p] += storage[offset(row, velocity)] * dt;
        }
    }

    /** Caller receives a detached immutable-by-ownership snapshot copy. */
    public double[] snapshotColumn(int field) {
        requireOwner();
        if (field < 0 || field >= fields) {
            throw new IndexOutOfBoundsException("invalid shard field");
        }
        double[] snapshot = new double[rows];
        for (int row = 0; row < rows; row++) snapshot[row] = storage[offset(row, field)];
        return snapshot;
    }
}
