package dev.oreslang.runtime;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Actor-owned 32-bit ECS/reference columns for AoS, SoA and AoSoA layouts.
 *
 * <p>This is the data-oriented counterpart to object references. Each cell is
 * a compact dense-index word: zero is null and non-zero values encode an
 * unsigned logical index via {@link ManagedReferencePolicy}. No Java object is
 * allocated per relationship and no pointer decoding is required while a DOD
 * kernel can operate on indices directly.</p>
 */
public final class ActorDenseIndexColumns extends ActorShardBacking implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final ActorShardColumns.Layout layout;
    private final int rows;
    private final int fields;
    private final int lanes;
    private int[] storage;
    private final ActorRuntime.ActorShardLease reservation;

    public ActorDenseIndexColumns(
            ActorShardColumns.Layout layout, int rows, int fields, int lanes) {
        ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("index shard creation requires an executing actor"));
        this.layout = Objects.requireNonNull(layout, "layout");
        if (rows <= 0 || fields <= 0) {
            throw new IllegalArgumentException("index shard dimensions must be positive");
        }
        if (lanes <= 0 || lanes > 1024 || (lanes & (lanes - 1)) != 0
                || (layout != ActorShardColumns.Layout.AOSOA && lanes != 1)) {
            throw new IllegalArgumentException("invalid index shard lane count");
        }
        this.rows = rows;
        this.fields = fields;
        this.lanes = lanes;

        long physicalRows = layout == ActorShardColumns.Layout.AOSOA
                ? Math.multiplyExact(
                        Math.addExact((long) rows, lanes - 1L) / lanes,
                        (long) lanes)
                : rows;
        long elements = Math.multiplyExact(physicalRows, (long) fields);
        if (elements > MAX_BYTES / Integer.BYTES) {
            throw new IllegalArgumentException("index shard exceeds 16 MiB host allocation cap");
        }
        int bytes = Math.toIntExact(Math.multiplyExact(elements, (long) Integer.BYTES));

        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        if (runtime == null) {
            throw new SecurityException("index shard allocation requires an owning runtime");
        }
        ActorRuntime.ActorShardLease allocated = runtime.reserveActorShard(bytes);
        int[] heap = null;
        try {
            if (!allocated.privateBacking()) heap = new int[Math.toIntExact(elements)];
        } catch (RuntimeException | Error failure) {
            allocated.close();
            throw failure;
        }
        this.reservation = allocated;
        this.storage = heap;
        try {
            allocated.attach(this);
        } catch (RuntimeException | Error failure) {
            revokeBackingFromLease();
            allocated.close();
            throw failure;
        }
    }

    @Override
    boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor) {
        return reservation.ownedBy(runtime, actor);
    }

    @Override
    boolean isPrivateBacked() {
        return reservation.privateBacking();
    }

    public ActorShardColumns.Layout layout() { return layout; }
    public int rows() { return rows; }
    public int fields() { return fields; }
    public int lanes() { return lanes; }

    @Override
    void revokeBackingFromLease() {
        int[] old = storage;
        if (old != null) {
            java.util.Arrays.fill(old, 0);
            storage = null;
        }
    }

    private void requireOwner() {
        reservation.requireOwner();
    }

    private int getEncoded(int index) {
        return reservation.privateBacking() ? reservation.readInt(index) : storage[index];
    }

    private void putEncoded(int index, int value) {
        if (reservation.privateBacking()) reservation.writeInt(index, value);
        else storage[index] = value;
    }

    private int offset(int row, int field) {
        if (row < 0 || row >= rows || field < 0 || field >= fields) {
            throw new IndexOutOfBoundsException("index shard row/field outside bounds");
        }
        return switch (layout) {
            case AOS -> row * fields + field;
            case SOA -> field * rows + row;
            case AOSOA -> ((row / lanes) * fields + field) * lanes + row % lanes;
        };
    }

    /**
     * Store a transient unsigned row index. This does not capture generation
     * or table identity; use a DenseIndexTable handle at durable boundaries.
     */
    public void writeIndex(int row, int field, long logicalIndex) {
        requireOwner();
        putEncoded(offset(row, field), ManagedReferencePolicy.encodeDenseIndex32(logicalIndex));
    }

    public void writeNull(int row, int field) {
        requireOwner();
        putEncoded(offset(row, field), 0);
    }

    /**
     * Read a transient unsigned row index. The returned number alone is not a
     * stable entity identity and must not outlive the relevant table epoch.
     */
    public OptionalLong readIndex(int row, int field) {
        return ManagedReferencePolicy.decodeDenseIndex32(readEncodedWord(row, field));
    }

    public int readEncodedWord(int row, int field) {
        requireOwner();
        return getEncoded(offset(row, field));
    }

    /**
     * Store raw physical bits for trusted layout/ABI plumbing. This bypasses
     * logical range encoding and never creates a generation/table proof.
     */
    public void writeEncodedWord(int row, int field, int encodedWord) {
        requireOwner();
        putEncoded(offset(row, field), encodedWord);
    }

    /**
     * Store a compact word only after its table identity, epoch and generation
     * have been validated. The four-byte column still stores only the word;
     * callers must retain the typed handle for later boundary validation.
     */
    public <TTag> void writeHandle(
            int row, int field, DenseIndex32<TTag> handle, DenseIndexTable<TTag> table) {
        requireOwner();
        Objects.requireNonNull(table, "table");
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        ActorRuntime.ActorId actor = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("typed dense index access requires an executing actor"));
        if (!table.ownedBy(runtime, actor)) {
            throw new SecurityException("dense index table belongs to a different actor");
        }
        int word = table.checkedStorageWord(handle).orElseThrow(() ->
                new IllegalStateException("foreign, stale or retired dense index handle"));
        putEncoded(offset(row, field), word);
    }

    /**
     * Check that a stored row word still denotes the supplied live table
     * identity. A matching integer without a live typed handle is insufficient.
     */
    public <TTag> boolean matchesHandle(
            int row, int field, DenseIndex32<TTag> handle, DenseIndexTable<TTag> table) {
        requireOwner();
        Objects.requireNonNull(table, "table");
        ActorRuntime runtime = ActorRuntime.currentActorRuntime();
        ActorRuntime.ActorId actor = ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("typed dense index access requires an executing actor"));
        if (!table.ownedBy(runtime, actor)) {
            throw new SecurityException("dense index table belongs to a different actor");
        }
        var checked = table.checkedStorageWord(handle);
        return checked.isPresent() && getEncoded(offset(row, field)) == checked.getAsInt();
    }

    /** Copy encoded 32-bit words without allocating per-index wrapper objects. */
    public void copyEncodedColumnInto(int field, int[] destination, int destinationOffset) {
        requireOwner();
        Objects.requireNonNull(destination, "destination");
        if (field < 0 || field >= fields) throw new IndexOutOfBoundsException("field");
        if (destinationOffset < 0 || destinationOffset > destination.length
                || rows > destination.length - destinationOffset) {
            throw new IndexOutOfBoundsException("column destination span");
        }
        for (int row = 0; row < rows; row++) {
            destination[destinationOffset + row] = getEncoded(offset(row, field));
        }
    }

    @Override
    public void close() {
        if (storage != null) {
            reservation.requireOwner();
            java.util.Arrays.fill(storage, 0);
            storage = null;
        }
        reservation.close();
    }
}
