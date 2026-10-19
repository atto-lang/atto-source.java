package dev.oreslang.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure compiler-side dependency planning for SoA/ECS columns.
 *
 * <p>No array copies, host threads or runtime locks. A backend may execute
 * each resulting stage with scoped worker Tasks after proving real region
 * identities and lifetime/Send obligations. Region symbols are compiler
 * identities, NOT unforgeable runtime capabilities or allocation pointers.
 */
public final class RegionAccessPlanner {
    private RegionAccessPlanner() { }

    public enum Mode { READ, WRITE }

    /** Canonical allocation identity and physical column, not an entity ID. */
    public record Region(String allocation, String column) {
        public Region {
            if (allocation == null || allocation.isBlank()
                    || column == null || column.isBlank()) {
                throw new IllegalArgumentException("region identity must be concrete");
            }
        }
    }

    /** Half-open element ranges [from, until), no implicit whole-world aliases. */
    public record Access(Region region, long from, long until, Mode mode) {
        public Access {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(mode, "mode");
            if (from < 0 || until <= from) {
                throw new IllegalArgumentException("region access range must be nonempty");
            }
        }
        public boolean conflicts(Access other) {
            return region.equals(other.region)
                    && from < other.until && other.from < until
                    && (mode == Mode.WRITE || other.mode == Mode.WRITE);
        }
    }

    /** Ordered source-system work unit with a verified access summary. */
    public record System(String name, List<Access> accesses) {
        public System {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("system name is required");
            }
            accesses = List.copyOf(accesses);
            if (accesses.isEmpty()) {
                throw new IllegalArgumentException(
                        "unknown access cannot be treated as empty; prove a pure system separately");
            }
        }
    }

    public record Stage(List<System> parallel) {
        public Stage { parallel = List.copyOf(parallel); }
    }

    /** Conservative alias check: every physical allocation must use a canonical ID. */
    public static boolean conflicts(System a, System b) {
        for (Access left : a.accesses()) {
            for (Access right : b.accesses()) {
                if (left.conflicts(right)) return true;
            }
        }
        return false;
    }

    /**
     * Stable conflict-dependency levels: any pair of conflicting systems
     * retains source order, while nonconflicting systems may share a stage.
     * This is a planner, not a borrow-checked execution permit.
     */
    public static List<Stage> plan(List<System> ordered) {
        List<System> source = List.copyOf(ordered);
        int[] levels = new int[source.size()];
        int maximum = -1;
        for (int i = 0; i < source.size(); i++) {
            int level = 0;
            for (int j = 0; j < i; j++) {
                if (conflicts(source.get(i), source.get(j))) {
                    level = Math.max(level, Math.addExact(levels[j], 1));
                }
            }
            levels[i] = level;
            maximum = Math.max(maximum, level);
        }
        List<Stage> stages = new ArrayList<>();
        for (int stage = 0; stage <= maximum; stage++) {
            List<System> batch = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                if (levels[i] == stage) batch.add(source.get(i));
            }
            stages.add(new Stage(batch));
        }
        return List.copyOf(stages);
    }

    /** Zero-copy disjoint partition descriptors for a contiguous column. */
    public static List<Access> disjointChunks(
            Region region, long length, long chunkSize, Mode mode) {
        Objects.requireNonNull(region);
        Objects.requireNonNull(mode);
        if (length <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException("length/chunkSize must be positive");
        }
        List<Access> chunks = new ArrayList<>();
        for (long start = 0; start < length;) {
            // Avoid start + chunkSize overflow.
            long end = start + Math.min(chunkSize, length - start);
            chunks.add(new Access(region, start, end, mode));
            start = end;
        }
        return List.copyOf(chunks);
    }
}
