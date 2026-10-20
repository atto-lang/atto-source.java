package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque runtime capability representing authority to execute against a region
 * and a statically described access set.
 *
 * <p>This is compiler/runtime infrastructure, not a guest-constructible value.
 * There is no public constructor or public minting API. A future compiler pass
 * will bind scoped borrowed Task captures to permits only after proving the
 * corresponding region/access lifetime.</p>
 *
 * <p>Split is linear: the parent permit is consumed only after the requested
 * partitions are proved pairwise-disjoint and exactly cover the parent's access
 * set. Rejoin requires the complete sibling set from one split lineage and
 * consumes every child atomically under that lineage lock.</p>
 */
public final class ExecutionPermit<R, A extends ExecutionPermit.AccessSet<A>> {

    /**
     * Immutable access-set algebra used by the runtime proof object.
     * Implementations must be value-like and side-effect free.
     */
    public interface AccessSet<A extends AccessSet<A>> {
        boolean overlaps(A other);
        A union(A other);
        boolean sameCoverage(A other);
    }

    private static final class Root<R> {
        private final R region;
        private long generation;

        private Root(R region, long generation) {
            this.region = Objects.requireNonNull(region, "region");
            if (generation < 0) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            this.generation = generation;
        }
    }

    private static final class SplitGroup<R, A extends AccessSet<A>> {
        private final Root<R> root;
        private final long generation;
        private final A parentAccess;
        private final SplitGroup<R, A> returnGroup;
        private final int returnIndex;
        private final int childCount;
        private boolean rejoined;

        private SplitGroup(
                Root<R> root,
                long generation,
                A parentAccess,
                SplitGroup<R, A> returnGroup,
                int returnIndex,
                int childCount) {
            this.root = root;
            this.generation = generation;
            this.parentAccess = parentAccess;
            this.returnGroup = returnGroup;
            this.returnIndex = returnIndex;
            this.childCount = childCount;
        }
    }

    private final Root<R> root;
    private final long generation;
    private final A access;
    private final SplitGroup<R, A> parentGroup;
    private final int parentIndex;
    private final AtomicBoolean live = new AtomicBoolean(true);

    private ExecutionPermit(
            Root<R> root,
            long generation,
            A access,
            SplitGroup<R, A> parentGroup,
            int parentIndex) {
        this.root = Objects.requireNonNull(root, "root");
        this.generation = generation;
        this.access = Objects.requireNonNull(access, "access");
        this.parentGroup = parentGroup;
        this.parentIndex = parentIndex;
    }

    /** Runtime/compiler-only root mint. Guest code has no path to this method. */
    static <R, A extends AccessSet<A>> ExecutionPermit<R, A> mintRoot(
            R region,
            long generation,
            A access) {
        Root<R> root = new Root<>(region, generation);
        return new ExecutionPermit<>(root, generation, access, null, -1);
    }

    boolean isLive() {
        synchronized (root) {
            return live.get() && root.generation == generation;
        }
    }

    long generation() {
        return generation;
    }

    A accessSet() {
        return access;
    }

    /**
     * Consume this permit and return a complete disjoint partition.
     */
    List<ExecutionPermit<R, A>> split(List<A> partitions) {
        Objects.requireNonNull(partitions, "partitions");
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("permit split requires at least one partition");
        }

        List<A> copy = List.copyOf(partitions);
        A covered = null;
        for (int i = 0; i < copy.size(); i++) {
            A part = Objects.requireNonNull(copy.get(i), "partition");
            for (int j = 0; j < i; j++) {
                if (part.overlaps(copy.get(j))) {
                    throw new IllegalArgumentException(
                            "permit split partitions overlap at indexes " + j + " and " + i);
                }
            }
            covered = covered == null ? part : covered.union(part);
        }
        if (covered == null || !covered.sameCoverage(access)) {
            throw new IllegalArgumentException(
                    "permit split partitions must exactly cover the parent access set");
        }

        synchronized (root) {
            requireLiveLocked("split");
            if (!live.compareAndSet(true, false)) {
                throw new IllegalStateException("permit was concurrently consumed");
            }

            SplitGroup<R, A> group = new SplitGroup<>(
                    root,
                    generation,
                    access,
                    parentGroup,
                    parentIndex,
                    copy.size());
            ArrayList<ExecutionPermit<R, A>> children =
                    new ArrayList<>(copy.size());
            for (int i = 0; i < copy.size(); i++) {
                children.add(new ExecutionPermit<>(
                        root, generation, copy.get(i), group, i));
            }
            return List.copyOf(children);
        }
    }

    /**
     * Rejoin the complete sibling set from one split operation.
     */
    static <R, A extends AccessSet<A>> ExecutionPermit<R, A> rejoin(
            List<ExecutionPermit<R, A>> children) {
        Objects.requireNonNull(children, "children");
        if (children.isEmpty()) {
            throw new IllegalArgumentException("permit rejoin requires children");
        }

        List<ExecutionPermit<R, A>> copy = List.copyOf(children);
        ExecutionPermit<R, A> first =
                Objects.requireNonNull(copy.getFirst(), "child permit");
        SplitGroup<R, A> group = first.parentGroup;
        if (group == null) {
            throw new IllegalArgumentException("root permit is not a split child");
        }

        synchronized (group) {
            if (group.rejoined) {
                throw new IllegalStateException("permit split group was already rejoined");
            }
            if (copy.size() != group.childCount) {
                throw new IllegalArgumentException(
                        "permit rejoin requires the complete sibling set");
            }

            Set<Integer> indexes = new HashSet<>();
            for (ExecutionPermit<R, A> child : copy) {
                Objects.requireNonNull(child, "child permit");
                if (child.parentGroup != group
                        || child.root != group.root
                        || child.generation != group.generation) {
                    throw new IllegalArgumentException(
                            "permit rejoin cannot mix split lineages or generations");
                }
                if (!indexes.add(child.parentIndex)) {
                    throw new IllegalArgumentException(
                            "permit rejoin contains a duplicate sibling");
                }
                synchronized (child.root) {
                    child.requireLiveLocked("rejoin");
                }
            }
            for (int i = 0; i < group.childCount; i++) {
                if (!indexes.contains(i)) {
                    throw new IllegalArgumentException(
                            "permit rejoin is missing sibling index " + i);
                }
            }

            // All validation is complete. Consume children only now.
            for (ExecutionPermit<R, A> child : copy) {
                if (!child.live.compareAndSet(true, false)) {
                    throw new IllegalStateException(
                            "permit child was concurrently consumed during rejoin");
                }
            }
            group.rejoined = true;
            return new ExecutionPermit<>(
                    group.root,
                    group.generation,
                    group.parentAccess,
                    group.returnGroup,
                    group.returnIndex);
        }
    }

    /**
     * Runtime-only region generation rotation. Only a fully rejoined root
     * permit can advance the generation; all prior-generation permits become
     * stale immediately.
     */
    ExecutionPermit<R, A> rotateGeneration(long nextGeneration, A nextAccess) {
        Objects.requireNonNull(nextAccess, "nextAccess");
        synchronized (root) {
            requireLiveLocked("rotate generation");
            if (parentGroup != null) {
                throw new IllegalStateException(
                        "only a fully rejoined root permit may rotate region generation");
            }
            if (nextGeneration <= generation) {
                throw new IllegalArgumentException(
                        "next generation must be greater than current generation");
            }
            if (!live.compareAndSet(true, false)) {
                throw new IllegalStateException("permit was concurrently consumed");
            }
            root.generation = nextGeneration;
            return new ExecutionPermit<>(
                    root, nextGeneration, nextAccess, null, -1);
        }
    }

    private void requireLiveLocked(String operation) {
        if (!live.get()) {
            throw new IllegalStateException(
                    "cannot " + operation + " with a consumed execution permit");
        }
        if (root.generation != generation) {
            throw new IllegalStateException(
                    "cannot " + operation + " with a stale execution permit generation");
        }
    }

    @Override
    public String toString() {
        return "ExecutionPermit[generation=" + generation
                + ",live=" + isLive() + "]";
    }
}
