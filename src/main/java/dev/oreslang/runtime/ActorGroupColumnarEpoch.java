package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One bounded, membership-fenced, host/runtime columnar collection epoch.
 *
 * <p>Actor turns contribute primitive scalar rows; the collector stores them
 * field-major. This is NOT permission to read actor private heaps or to share
 * mutable actor state. It is a first executable host substrate, not guest
 * ActorGroup/InvertedArray lowering or a zero-copy shared-region view.</p>
 */
public final class ActorGroupColumnarEpoch {
    /** Immutable-on-publication field-major snapshot. No mutable arrays escape. */
    public static final class Snapshot {
        private final long epochId;
        private final List<ActorRuntime.ActorId> members;
        private final List<String> fields;
        private final double[][] columns;

        private Snapshot(long epochId, List<ActorRuntime.ActorId> members,
                List<String> fields, double[][] columns) {
            this.epochId = epochId;
            this.members = members;
            this.fields = fields;
            // Called only after the final publisher has committed, under the
            // epoch monitor. Future reads do not race with column writes.
            this.columns = columns;
        }

        public long epochId() { return epochId; }
        public List<ActorRuntime.ActorId> members() { return members; }
        public List<String> fields() { return fields; }
        public int rowCount() { return members.size(); }
        public int columnCount() { return fields.size(); }

        public double value(int row, int column) { return columns[column][row]; }

        public double[] column(int column) { return columns[column].clone(); }

        public double[] column(String field) {
            int index = fields.indexOf(Objects.requireNonNull(field, "field"));
            if (index < 0) throw new IllegalArgumentException("unknown result field: " + field);
            return column(index);
        }
    }

    private static final int MAX_FIELDS = 16;
    private static final long MAX_BUFFER_BYTES = 16L * 1024 * 1024;

    private final long epochId;
    private final List<ActorRuntime.ActorId> members;
    private final List<String> fields;
    private final Map<ActorRuntime.ActorId, Integer> indices;
    private double[][] columns;
    private final boolean[] published;
    private final OresFuture<Snapshot> completion =
            new OresFuture<>(() -> false, () -> { });
    private int remaining;
    private boolean terminal;

    ActorGroupColumnarEpoch(long epochId, List<ActorRuntime.ActorId> memberIds,
            List<String> fieldNames) {
        if (epochId <= 0) throw new IllegalArgumentException("epoch ID must be positive");
        Objects.requireNonNull(memberIds, "memberIds");
        Objects.requireNonNull(fieldNames, "fieldNames");
        if (fieldNames.isEmpty() || fieldNames.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("column count must be 1.." + MAX_FIELDS);
        }
        if (!fieldNames.stream().allMatch(name -> name != null && !name.isBlank())
                || new HashSet<>(fieldNames).size() != fieldNames.size()) {
            throw new IllegalArgumentException("result field names must be nonblank and unique");
        }
        long byteCount = Math.multiplyExact(
                Math.multiplyExact((long) memberIds.size(), fieldNames.size()), Double.BYTES);
        if (byteCount > MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException("columnar epoch exceeds 16 MiB cap");
        }
        this.epochId = epochId;
        this.members = List.copyOf(memberIds);
        this.fields = List.copyOf(fieldNames);
        this.remaining = members.size();
        this.columns = new double[fields.size()][members.size()];
        this.published = new boolean[members.size()];
        Map<ActorRuntime.ActorId, Integer> positions = new HashMap<>();
        for (int i = 0; i < members.size(); i++) {
            if (positions.putIfAbsent(members.get(i), i) != null) {
                throw new IllegalArgumentException("duplicate actor in epoch");
            }
        }
        this.indices = Map.copyOf(positions);
    }

    public long epochId() { return epochId; }
    public List<ActorRuntime.ActorId> members() { return members; }
    public List<String> fields() { return fields; }
    public OresFuture<Snapshot> completion() { return completion; }

    synchronized boolean terminal() { return terminal; }
    synchronized boolean expects(ActorRuntime.ActorId actorId) {
        return indices.containsKey(actorId);
    }

    /**
     * Called under the ActorGroup membership lock; returns the result only if
     * this row completed the epoch. Caller settles the Future outside locks.
     */
    synchronized Snapshot publish(ActorRuntime.ActorId actorId, double[] values) {
        Objects.requireNonNull(values, "values");
        if (terminal) throw new IllegalStateException("columnar epoch is no longer active");
        Integer row = indices.get(actorId);
        if (row == null) throw new SecurityException("actor absent from epoch membership snapshot");
        if (published[row]) throw new IllegalStateException("duplicate actor epoch publication");
        if (values.length != fields.size()) {
            throw new IllegalArgumentException("result row requires " + fields.size() + " columns");
        }
        for (int i = 0; i < values.length; i++) columns[i][row] = values[i];
        published[row] = true;
        remaining--;
        if (remaining != 0) return null;
        terminal = true;
        return new Snapshot(epochId, members, fields, columns);
    }

    synchronized Snapshot emptySnapshot() {
        if (terminal || remaining != 0) return null;
        terminal = true;
        return new Snapshot(epochId, members, fields, columns);
    }

    /** Stop accepting producers. Do not call the Future while holding group locks. */
    synchronized boolean abort() {
        if (terminal) return false;
        terminal = true;
        columns = null; // release unfinished result storage on revocation
        return true;
    }

    void succeed(Snapshot snapshot) {
        completion.completeFromRuntime(Objects.requireNonNull(snapshot, "snapshot"));
    }

    void fail(Throwable cause) {
        completion.failFromRuntime(Objects.requireNonNull(cause, "cause"));
    }
}
