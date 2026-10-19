package dev.oreslang.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Conservative read/write dependency planner for zero-copy ECS region slices.
 * A plan is not an execution grant: scheduler-minted region-bound permits and
 * physical Task quiescence remain prerequisites for parallel dispatch.
 */
public final class RegionAccessPlanner {
    private RegionAccessPlanner() { }

    public enum Mode { READ, WRITE, STRUCTURAL }

    public record Access(long regionId, long generation, String column,
                         long start, long end, Mode mode) {
        public Access {
            if (regionId <= 0 || generation <= 0)
                throw new IllegalArgumentException("invalid region incarnation");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(mode, "mode");
            if (column.isBlank() || start < 0 || end <= start)
                throw new IllegalArgumentException("empty or invalid region range");
            if (mode == Mode.STRUCTURAL && !column.equals("*"))
                throw new IllegalArgumentException("structural mutations must cover the entire region");
            if (mode != Mode.STRUCTURAL && column.equals("*"))
                throw new IllegalArgumentException("wildcard access is exclusive structural mutation only");
        }
    }

    public record SystemAccess(String name, List<Access> accesses) {
        public SystemAccess {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("missing system name");
            accesses = List.copyOf(accesses);
            if (accesses.isEmpty())
                throw new IllegalArgumentException("system access set must be explicit");
            java.util.Map<Long, Long> generations = new java.util.HashMap<>();
            for (Access access : accesses) {
                Long previous = generations.putIfAbsent(access.regionId(), access.generation());
                if (previous != null && previous.longValue() != access.generation())
                    throw new IllegalArgumentException("mixed or stale region generation in one system");
            }
            // Two contradictory effects on an overlapping slice cannot be
            // interpreted as independent borrows within the same system.
            for (int i = 0; i < accesses.size(); i++) {
                for (int j = i + 1; j < accesses.size(); j++) {
                    if (overlapsMutably(accesses.get(i), accesses.get(j)))
                        throw new IllegalArgumentException("overlapping write effects in one system");
                }
            }
        }
    }

    private static boolean overlapsMutably(Access a, Access b) {
        if (a.regionId() != b.regionId()) return false;
        if (a.mode() == Mode.STRUCTURAL || b.mode() == Mode.STRUCTURAL)
            return true;
        return a.column().equals(b.column())
                && a.start() < b.end() && b.start() < a.end()
                && (a.mode() == Mode.WRITE || b.mode() == Mode.WRITE);
    }

    private static void assertConsistentGenerations(Iterable<SystemAccess> systems) {
        java.util.Map<Long, Long> generations = new java.util.HashMap<>();
        for (SystemAccess system : systems) {
            for (Access access : system.accesses()) {
                Long known = generations.putIfAbsent(access.regionId(), access.generation());
                if (known != null && known.longValue() != access.generation())
                    throw new IllegalArgumentException("stale or mismatched region generation");
            }
        }
    }

    public static boolean conflicts(SystemAccess first, SystemAccess second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        // Validate all region generations BEFORE the first conflict can
        // short-circuit; an earlier overlap must not conceal stale metadata.
        assertConsistentGenerations(List.of(first, second));
        return conflictsWithValidatedGenerations(first, second);
    }

    private static boolean conflictsWithValidatedGenerations(
            SystemAccess first, SystemAccess second) {
        for (Access a : first.accesses()) {
            for (Access b : second.accesses()) {
                if (overlapsMutably(a, b)) return true;
            }
        }
        return false;
    }

    /**
     * Dependency stages preserve the relative order of conflicting systems.
     * Unlike a naive first-fit batch, a later system cannot overtake an earlier
     * conflicting system that was itself delayed by another dependency.
     */
    public static List<List<SystemAccess>> plan(List<SystemAccess> ordered) {
        Objects.requireNonNull(ordered, "ordered");
        // Validate whole-batch incarnations even if a scheduling shortcut
        // would otherwise avoid comparing two systems directly.
        assertConsistentGenerations(ordered);
        List<SystemAccess> visited = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        List<Integer> levels = new ArrayList<>();
        List<List<SystemAccess>> stages = new ArrayList<>();
        for (SystemAccess current : ordered) {
            if (!names.add(current.name()))
                throw new IllegalArgumentException("duplicate ECS system identity");
            int level = 0;
            for (int i = 0; i < visited.size(); i++) {
                if (conflictsWithValidatedGenerations(current, visited.get(i)))
                    level = Math.max(level, levels.get(i) + 1);
            }
            while (stages.size() <= level) stages.add(new ArrayList<>());
            stages.get(level).add(current);
            visited.add(current);
            levels.add(level);
        }
        List<List<SystemAccess>> frozen = new ArrayList<>();
        for (List<SystemAccess> stage : stages) frozen.add(List.copyOf(stage));
        return List.copyOf(frozen);
    }
}
