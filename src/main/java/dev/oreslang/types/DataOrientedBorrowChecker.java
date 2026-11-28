package dev.oreslang.types;

import java.util.List;
import java.util.Objects;

/**
 * Canonical alias/access-set rules for AoS, SoA, AoSoA and ECS storage.
 *
 * <p>Physical layout is intentionally not an aliasing permission. The checker
 * reasons over logical places: store -> archetype -> component -> field ->
 * row/chunk range. Sibling fields/components and disjoint row ranges can be
 * borrowed independently; a whole-place access overlaps all descendants.
 */
public final class DataOrientedBorrowChecker {
    private DataOrientedBorrowChecker() { }

    // Bound quadratic admission over logical worksets. This is a conservative
    // compiler admission budget, not a runtime actor mailbox quota.
    public static final int MAX_LOANS_PER_WORKSET = 1024;

    public enum Layout { AOS, SOA, AOSOA }
    public enum Access { READ, WRITE }

    /** Half-open row/lane interval [start, end). Null means all rows. */
    public record Range(long start, long end) {
        public Range {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid DOP borrow range");
            }
        }

        boolean overlaps(Range other) {
            return start < other.end && other.start < end;
        }
    }

    /** Largest write granule accepted; matches the AoSoA lane / tile cap. */
    public static final long MAX_WRITE_GRANULE = 1024;

    /**
     * Null archetype/component/field is the whole parent place. Epoch fences
     * relocation, compaction and explicit relayout.
     *
     * <p>{@code granule} is the number of consecutive rows a writer may touch as
     * one unit: the AoSoA lane count for tile-wide stores, the vector width for
     * unmasked SIMD stores over SoA columns, or 1 for scalar row access. Two row
     * ranges that are logically disjoint but split a granule are NOT
     * independently writable: a full-tile vector store to rows [8,16) clobbers
     * rows [12,16) owned by another task. Write conflicts are therefore decided
     * on ranges widened to granule boundaries.</p>
     */
    public record Place(
            Layout layout,
            String store,
            String archetype,
            String component,
            String field,
            Range rows,
            long epoch,
            long granule) {
        /** Scalar (granule 1) place; kept for existing callers. */
        public Place(Layout layout, String store, String archetype, String component,
                     String field, Range rows, long epoch) {
            this(layout, store, archetype, component, field, rows, epoch, 1);
        }

        public Place {
            if (granule < 1 || granule > MAX_WRITE_GRANULE || (granule & (granule - 1)) != 0) {
                throw new IllegalArgumentException(
                        "DOP write granule must be a power of two in 1.." + MAX_WRITE_GRANULE);
            }
            Objects.requireNonNull(layout, "layout");
            if (store == null || store.isBlank()) {
                throw new IllegalArgumentException("DOP place requires store identity");
            }
            requireNonBlankIfPresent(archetype, "archetype");
            requireNonBlankIfPresent(component, "component");
            requireNonBlankIfPresent(field, "field");
            if (epoch < 0) throw new IllegalArgumentException("negative storage epoch");
            // Logical place hierarchy is strict. A component without an
            // archetype must not look independent of a whole-archetype loan.
            // Rooted projection proof is separate from physical AoS/SoA layout.
            if (archetype == null && component != null) {
                throw new IllegalArgumentException("component requires archetype");
            }
            if (archetype == null && component != null) {
                throw new IllegalArgumentException("component requires archetype");
            }
            if (component == null && field != null) {
                throw new IllegalArgumentException("field requires component");
            }
        }
    }

    public record Loan(Place place, Access access) {
        public Loan {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(access, "access");
        }
    }

    /**
     * Conservative source-level collection loan policy (candidate v1).
     *
     * <p>A collection element is rooted at its allocating collection archetype,
     * never at a syntactically different row/field. DOP's proven disjoint-row
     * scheduling is a separate capability and must NOT be inferred from
     * collection indices. The compiler must provide authenticated allocation
     * and actor identity in the store/root; this host helper does not prove it.
     */
    public static Loan conservativeCollectionRootLoan(Place projection, Access access) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(access, "access");
        if (projection.archetype() == null) {
            throw new IllegalArgumentException(
                    "collection source loan requires rooted collection identity");
        }
        Place root = new Place(projection.layout(), projection.store(),
                projection.archetype(), null, null, null, projection.epoch());
        return new Loan(root, access);
    }

    public static void requireCurrentEpoch(Place place, long currentEpoch) {
        if (currentEpoch < 0 || place.epoch() != currentEpoch) {
            throw new IllegalArgumentException(
                    "stale DOP borrow: place epoch " + place.epoch()
                            + " != current epoch " + currentEpoch);
        }
    }

    /** Logical aliasing: do the two places name any common cell? */
    public static boolean overlaps(Place left, Place right) {
        return overlaps(left, right, 1);
    }

    private static boolean overlaps(Place left, Place right, long granule) {
        if (!left.store().equals(right.store())) return false;
        if (left.epoch() != right.epoch()) {
            throw new IllegalArgumentException(
                    "cannot compare DOP places from different storage epochs: "
                            + left.epoch() + " vs " + right.epoch());
        }
        if (!sameOrWhole(left.archetype(), right.archetype())) return false;
        if (!sameOrWhole(left.component(), right.component())) return false;
        if (!sameOrWhole(left.field(), right.field())) return false;
        return left.rows() == null || right.rows() == null
                || widen(left.rows(), granule).overlaps(widen(right.rows(), granule));
    }

    /**
     * Read/read never conflicts. When either side writes, ranges are compared at
     * the coarser of the two write granules (powers of two, so the larger is a
     * multiple of the smaller and covers both tilings).
     */
    public static boolean conflicts(Loan left, Loan right) {
        boolean writes = left.access() == Access.WRITE || right.access() == Access.WRITE;
        long granule = writes ? Math.max(left.place().granule(), right.place().granule()) : 1;
        // Always run the overlap test first so stale epochs still fail closed.
        return overlaps(left.place(), right.place(), granule) && writes;
    }

    private static Range widen(Range range, long granule) {
        if (granule == 1) return range;
        long start = range.start() - Math.floorMod(range.start(), granule);
        long rem = Math.floorMod(range.end(), granule);
        long end = rem == 0 ? range.end()
                : (range.end() > Long.MAX_VALUE - (granule - rem) ? Long.MAX_VALUE : range.end() + (granule - rem));
        return new Range(start, end);
    }

    public static boolean canRunConcurrently(List<Loan> left, List<Loan> right) {
        requireBudget(left);
        requireBudget(right);
        left = List.copyOf(left);
        right = List.copyOf(right);
        // A malformed individual workset must never be admitted simply
        // because the competing workset is empty or disjoint. Check both
        // sets before looking at pairwise cross-set scheduling conflicts.
        requireInternallyValid(left);
        requireInternallyValid(right);
        for (Loan a : left) {
            for (Loan b : right) {
                if (conflicts(a, b)) return false;
            }
        }
        return true;
    }

    public static void requireInternallyValid(List<Loan> loans) {
        requireBudget(loans);
        loans = List.copyOf(loans);
        for (int i = 0; i < loans.size(); i++) {
            for (int j = i + 1; j < loans.size(); j++) {
                if (conflicts(loans.get(i), loans.get(j))) {
                    throw new IllegalArgumentException(
                            "conflicting DOP loans: " + loans.get(i)
                                    + " overlaps " + loans.get(j));
                }
            }
        }
    }

    private static void requireBudget(List<Loan> loans) {
        Objects.requireNonNull(loans, "loans");
        if (loans.size() > MAX_LOANS_PER_WORKSET) {
            throw new IllegalArgumentException("DOP loan workset exceeds admission budget");
        }
    }

    private static boolean sameOrWhole(String left, String right) {
        return left == null || right == null || left.equals(right);
    }

    private static void requireNonBlankIfPresent(String value, String label) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    "DOP " + label + " identity cannot be blank");
        }
    }
}

