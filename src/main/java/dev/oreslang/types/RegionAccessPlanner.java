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
        }
    }

    public static boolean conflicts(SystemAccess first, SystemAccess second) {
        for (Access a : first.accesses()) {
            for (Access b : second.accesses()) {
                if (a.regionId() != b.regionId()) continue;
                if (a.generation() != b.generation())
                    throw new IllegalArgumentException("stale or mismatched region generation");
                if (a.mode() == Mode.STRUCTURAL || b.mode() == Mode.STRUCTURAL)
                    return true;
                if (!a.column().equals(b.column())) continue;
                if (a.start() < b.end() && b.start() < a.end()
                        && (a.mode() == Mode.WRITE || b.mode() == Mode.WRITE))
                    return true;
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
        List<SystemAccess> visited = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<List<SystemAccess>> stages = new ArrayList<>();
        for (SystemAccess current : ordered) {
            int level = 0;
            for (int i = 0; i < visited.size(); i++) {
                if (conflicts(current, visited.get(i)))
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
