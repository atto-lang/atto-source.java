package dev.oreslang.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Conservative compile-time ECS/DOP system-access batching.
 *
 * <p>This is analysis metadata, NOT a runtime access permit or borrow splitter.
 * The caller must use canonical allocation identities established by the
 * compiler's provenance analysis. Null means alias provenance is unknown;
 * such an access cannot be assumed disjoint from any other allocation.
 *
 * <p>Intervals are half-open byte spans [start, end). Different physical
 * component columns may run in parallel; overlapping writers serialize.
 * Workers must separately acquire scoped, runtime-enforced region loans
 * before touching memory. A plan cannot authorize access by itself.
 */
public final class EcsAccessPlanner {
    private EcsAccessPlanner() { }

    public enum Mode { READ, WRITE }

    public record Access(Object allocationIdentity, long start, long end, Mode mode) {
        public Access {
            Objects.requireNonNull(mode, "access mode");
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("ECS byte span must be nonempty and nonnegative");
            }
        }
    }

    public record SystemAccess(String name, List<Access> accesses) {
        public SystemAccess {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ECS system needs a name");
            }
            accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
        }
    }

    /** True if two systems cannot safely overlap according to proven spans. */
    public static boolean conflicts(SystemAccess left, SystemAccess right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        for (Access a : left.accesses()) {
            for (Access b : right.accesses()) {
                if (a.mode() == Mode.READ && b.mode() == Mode.READ) continue;
                // Unknown alias provenance is global: even disjoint-looking
                // offsets are not a proof when allocations cannot be traced.
                if (a.allocationIdentity() == null || b.allocationIdentity() == null) return true;
                if (a.allocationIdentity() != b.allocationIdentity()) continue;
                if (a.start() < b.end() && b.start() < a.end()) return true;
            }
        }
        return false;
    }

    /**
     * Stable, order-preserving batches: all systems in a batch commute by
     * access footprints. A conflicting system starts a NEW batch; it must not
     * backfill an earlier batch and overtake an intervening dependency.
     *
     * <p>No physical execution, threads, memory loans or reordering happen here.
     */
    public static List<List<SystemAccess>> plan(List<SystemAccess> systems) {
        Objects.requireNonNull(systems, "systems");
        List<List<SystemAccess>> batches = new ArrayList<>();
        List<SystemAccess> current = new ArrayList<>();
        for (SystemAccess system : systems) {
            Objects.requireNonNull(system, "system");
            boolean conflict = false;
            for (SystemAccess existing : current) {
                if (conflicts(existing, system)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                batches.add(List.copyOf(current));
                current.clear();
            }
            current.add(system);
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return List.copyOf(batches);
    }
}
