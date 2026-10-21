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

    /**
     * Half-open element ranges [from, until), no implicit whole-world aliases.
     *
     * Optional byteBase/elementBytes are compiler-proven physical geometry for
     * cache scheduling only. They never strengthen ownership or dereference
     * authority. The four-argument constructor intentionally means unknown
     * physical layout.
     */
    public record Access(Region region, long from, long until, Mode mode,
                         Long byteBase, Integer elementBytes) {
        public Access(Region region, long from, long until, Mode mode) {
            this(region, from, until, mode, null, null);
        }
        public Access {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(mode, "mode");
            if (from < 0 || until <= from) {
                throw new IllegalArgumentException("region access range must be nonempty");
            }
            if ((byteBase == null) != (elementBytes == null)
                    || (byteBase != null && (byteBase < 0 || elementBytes <= 0))) {
                throw new IllegalArgumentException(
                        "physical region geometry must be complete and positive");
            }
            if (byteBase != null) {
                // Reject impossible compiler metadata now, rather than during
                // a hot scheduling decision.
                try {
                    Math.addExact(byteBase,
                            Math.multiplyExact(until, (long) elementBytes));
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(
                            "physical region geometry overflows byte address space", overflow);
                }
            }
        }
        public boolean conflicts(Access other) {
            return sameLogicalColumn(other)
                    && from < other.until && other.from < until
                    && (mode == Mode.WRITE || other.mode == Mode.WRITE);
        }
        private boolean sameLogicalColumn(Access other) {
            return region.allocation().equals(other.region.allocation())
                    && region.column().equals(other.region.column());
        }
        private boolean hasPhysicalGeometry() {
            return byteBase != null;
        }
        private long byteFrom() {
            return Math.addExact(byteBase,
                    Math.multiplyExact(from, (long) elementBytes));
        }
        private long byteUntil() {
            return Math.addExact(byteBase,
                    Math.multiplyExact(until, (long) elementBytes));
        }
        public boolean cacheLineConflicts(Access other, int cacheLineBytes) {
            Objects.requireNonNull(other, "other");
            validateCacheLineBytes(cacheLineBytes);
            if (conflicts(other)) return true;
            if (mode != Mode.WRITE && other.mode != Mode.WRITE) return false;
            if (!region.allocation().equals(other.region.allocation())
                    || !hasPhysicalGeometry() || !other.hasPhysicalGeometry()) {
                return false;
            }
            long leftStartLine = byteFrom() / cacheLineBytes;
            long leftEndLine = (byteUntil() - 1L) / cacheLineBytes;
            long rightStartLine = other.byteFrom() / cacheLineBytes;
            long rightEndLine = (other.byteUntil() - 1L) / cacheLineBytes;
            return leftStartLine <= rightEndLine && rightStartLine <= leftEndLine;
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
     * Performance-only conflict relation. Correctness conflicts are always
     * retained. When both accesses carry compiler-proven byte geometry, a
     * shared cache line containing at least one writer is also serialized.
     */
    public static boolean cacheLineConflicts(System a, System b, int cacheLineBytes) {
        validateCacheLineBytes(cacheLineBytes);
        for (Access left : a.accesses()) {
            for (Access right : b.accesses()) {
                if (left.cacheLineConflicts(right, cacheLineBytes)) return true;
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
        return planInternal(ordered, null);
    }

    /**
     * Preserve correctness dependencies and additionally avoid proven
     * same-cache-line writer interference. Unknown byte geometry does not
     * invent a cache conflict; callers simply lack a false-sharing proof.
     */
    public static List<Stage> planCacheAware(
            List<System> ordered, int cacheLineBytes) {
        validateCacheLineBytes(cacheLineBytes);
        return planInternal(ordered, cacheLineBytes);
    }

    private static List<Stage> planInternal(
            List<System> ordered, Integer cacheLineBytes) {
        List<System> source = List.copyOf(ordered);
        int[] levels = new int[source.size()];
        int maximum = -1;
        for (int i = 0; i < source.size(); i++) {
            int level = 0;
            for (int j = 0; j < i; j++) {
                boolean conflict = cacheLineBytes == null
                        ? conflicts(source.get(i), source.get(j))
                        : cacheLineConflicts(
                                source.get(i), source.get(j), cacheLineBytes);
                if (conflict) {
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

    private static void validateCacheLineBytes(int cacheLineBytes) {
        if (cacheLineBytes < 16 || cacheLineBytes > 4096
                || (cacheLineBytes & (cacheLineBytes - 1)) != 0) {
            throw new IllegalArgumentException("invalid cache-line width");
        }
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

    /**
     * Partition one physically described column so every *interior* boundary
     * lands on a cache-line boundary when an exact element boundary exists.
     * This prevents adjacent worker writers from sharing the boundary line.
     * The final region edge may still share a line with a neighboring column;
     * physical layout padding must address that separate concern.
     */
    public static List<Access> cacheSafeDisjointChunks(
            Region region, long length, long targetChunkElements, Mode mode,
            long byteBase, int elementBytes, int cacheLineBytes) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(mode, "mode");
        validateCacheLineBytes(cacheLineBytes);
        if (length <= 0 || targetChunkElements <= 0
                || byteBase < 0 || elementBytes <= 0) {
            throw new IllegalArgumentException("invalid cache-safe chunk geometry");
        }
        long period = cacheLineBytes / gcd(cacheLineBytes, elementBytes);
        if (Math.floorMod(byteBase, gcd(cacheLineBytes, elementBytes)) != 0) {
            throw new IllegalArgumentException(
                    "no element boundary can align this region to a cache line");
        }

        List<Access> chunks = new ArrayList<>();
        long start = 0;
        while (start < length) {
            long remaining = length - start;
            if (remaining <= targetChunkElements) {
                chunks.add(new Access(
                        region, start, length, mode, byteBase, elementBytes));
                break;
            }
            long desired = Math.addExact(start, targetChunkElements);
            long end = nextAlignedElement(
                    byteBase, desired, length, elementBytes,
                    cacheLineBytes, period);
            if (end <= start || end >= length) {
                // No useful interior boundary remains; one final chunk avoids
                // inventing a false cache-isolation guarantee.
                chunks.add(new Access(
                        region, start, length, mode, byteBase, elementBytes));
                break;
            }
            chunks.add(new Access(
                    region, start, end, mode, byteBase, elementBytes));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static long nextAlignedElement(
            long byteBase, long atLeast, long length, int elementBytes,
            int cacheLineBytes, long period) {
        long search = Math.min(period, length - atLeast);
        long limit = atLeast + search;
        for (long element = atLeast; element < limit; element++) {
            long offset = Math.addExact(
                    byteBase, Math.multiplyExact(element, (long) elementBytes));
            if (offset % cacheLineBytes == 0) return element;
        }
        return length;
    }

    private static int gcd(int a, int b) {
        int x = Math.abs(a), y = Math.abs(b);
        while (y != 0) {
            int next = x % y;
            x = y;
            y = next;
        }
        return x;
    }
}
