package dev.oreslang.runtime;

import dev.oreslang.runtime.accel.DopKernelIR;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-side DOP dependency planner; no storage authority or GPU dispatch.
 * Region roots are identity tokens minted by trusted runtime code only.
 */
public final class DopRegionSchedule {
    private DopRegionSchedule() { }

    public enum Mode { READ, WRITE, DISCARD, REDUCE, ATOMIC }

    public static final class Root {
        private final long rows;
        private final int fields;
        private final DopKernelIR.HostBuffer.RegionToken backing;
        private Root(long rows, int fields, DopKernelIR.HostBuffer.RegionToken backing) {
            if (rows < 0 || fields <= 0) throw new IllegalArgumentException("invalid region shape");
            this.rows = rows;
            this.fields = fields;
            this.backing = backing;
        }
        // Logical-only roots are for planning/tests and carry NO access authority.
        static Root trustedCreate(long rows, int fields) { return new Root(rows, fields, null); }

        /** Trusted host call: bind root identity to a real owned allocation. */
        public static Root fromHostBuffer(DopKernelIR.HostBuffer buffer) {
            Objects.requireNonNull(buffer, "buffer");
            var plan = buffer.layout();
            // State identity, not wrapper identity, survives take().
            var backing = buffer.regionBackingIdentity();
            return new Root(plan.rows(), plan.fields(), backing);
        }
        public boolean sameRoot(Root other) {
            Objects.requireNonNull(other, "other");
            return this == other || (backing != null && backing == other.backing);
        }
        public boolean isLiveOwned() {
            return backing != null && DopKernelIR.HostBuffer.isBackingLive(backing);
        }
        public long rows() { return rows; }
        public int fields() { return fields; }
    }

    public record Access(Root root, int field, long begin, long end,
                         Mode mode, String reduction) {
        public Access {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(mode, "mode");
            if (field < 0 || field >= root.fields || begin < 0 || begin > root.rows
                    || end < begin || end > root.rows) {
                throw new IllegalArgumentException("DOP field or row slice out of bounds");
            }
            if ((mode == Mode.REDUCE && (reduction == null || reduction.isBlank()
                    || !reduction.equals(reduction.trim())))
                    || (mode != Mode.REDUCE && reduction != null)) {
                throw new IllegalArgumentException("reduction requires exactly one named operator");
            }
        }
        public static Access rows(Root root, int field, long begin, long count,
                                  Mode mode, String reduction) {
            Objects.requireNonNull(root, "root");
            if (count < 0 || begin < 0 || begin > root.rows || count > root.rows - begin) {
                throw new IllegalArgumentException("DOP row count out of bounds");
            }
            return new Access(root, field, begin, begin + count, mode, reduction);
        }
        public static Access rows(Root root, int field, long begin, long count, Mode mode) {
            return rows(root, field, begin, count, mode, null);
        }
    }

    public record Task(String name, List<Access> accesses) {
        public Task {
            if (name == null || name.isBlank() || !name.equals(name.trim()))
                throw new IllegalArgumentException("invalid DOP task name");
            accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
        }
    }

    public static boolean independent(Access a, Access b) {
        Objects.requireNonNull(a, "left");
        Objects.requireNonNull(b, "right");
        if (!a.root().sameRoot(b.root()) || a.field() != b.field()
                || a.begin() == a.end() || b.begin() == b.end()
                || a.end() <= b.begin() || b.end() <= a.begin()) return true;
        // Even same-op reductions serialize without a certified reduction tree.
        return a.mode() == Mode.READ && b.mode() == Mode.READ;
    }

    public static boolean independent(Task a, Task b) {
        Objects.requireNonNull(a, "left");
        Objects.requireNonNull(b, "right");
        for (Access left : a.accesses()) {
            for (Access right : b.accesses()) {
                if (!independent(left, right)) return false;
            }
        }
        return true;
    }

    /**
     * Physical preflight only; NOT an execution lease. Callers must acquire
     * and retain actual HostBuffer ReadLoan/WriteLoan through the operation.
     */
    public static void requireLiveOwned(Task task) {
        Objects.requireNonNull(task, "task");
        for (var access : task.accesses()) {
            if (!access.root().isLiveOwned()) {
                throw new IllegalStateException("DOP region has no live owned allocation");
            }
        }
    }

    /** Stable batch order: each conflicting task follows all its predecessors. */
    public static List<List<Integer>> dependencyLevels(List<Task> tasks) {
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty()) return List.of();
        int[] level = new int[tasks.size()];
        int highest = 0;
        for (int i = 0; i < tasks.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (!independent(tasks.get(i), tasks.get(j))) {
                    level[i] = Math.max(level[i], Math.addExact(level[j], 1));
                }
            }
            highest = Math.max(highest, level[i]);
        }
        List<List<Integer>> batches = new ArrayList<>();
        for (int i = 0; i <= highest; i++) batches.add(new ArrayList<>());
        for (int i = 0; i < tasks.size(); i++) batches.get(level[i]).add(i);
        return batches.stream().map(List::copyOf).toList();
    }
}
