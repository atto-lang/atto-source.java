package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-side, conservative loan-footprint verifier for structured child Tasks.
 *
 * <p>This is NOT an executable task scheduler, an OS-thread token, an allocation
 * capability, or a guest-callable API. The frontend must separately prove
 * Send/Sync, lifetime/outlives, allocation-domain admission, and capture effects
 * before placing a claim here. Only trusted compiler lowering can declare a
 * field or range layout-proven. Unknown aliasing always conflicts.
 *
 * <p>Admission is atomic across a Task's claims and ledger operations are
 * synchronized (this is a compiler analysis helper, not runtime host admission).
 * Cancellation does not release loans: the emitted TaskScope quiescence barrier
 * must be proven before
 * calling {@link #quiescent(Ticket)}.
 */
final class TaskAccessVerifier {
    public enum Mode { READ, WRITE }

    /**
     * Canonical allocation provenance from the borrow checker. Distinct IDs
     * prove nonaliasing only when BOTH roots have compiler-verified uniqueness.
     * A binding name, OS thread ID or value-equal user token cannot supply it.
     */
    public record Root(String domain, long allocationId, long generation,
                       boolean uniqueAllocationProven) {
        public Root {
            if (domain == null || domain.isBlank() || allocationId <= 0 || generation <= 0) {
                throw new IllegalArgumentException("invalid allocation provenance");
            }
        }
    }

    public sealed interface Projection permits Field, Range { }

    /** Verified inline field storage, not a pointer/FFI alias to arbitrary memory. */
    public record Field(String name, boolean disjointLayoutProven) implements Projection {
        public Field {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("empty field");
        }
    }

    /** Half-open byte/element span with checked layout and backing-allocation identity. */
    public record Range(long start, long end, boolean disjointLayoutProven)
            implements Projection {
        public Range {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid half-open range");
            }
        }
    }

    public record Place(Root root, List<Projection> projections) {
        public Place {
            Objects.requireNonNull(root, "root");
            projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
            if (projections.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("null projection");
            }
        }

        public static Place whole(Root root) { return new Place(root, List.of()); }

        public Place field(String name, boolean provenInlineLayout) {
            ArrayList<Projection> next = new ArrayList<>(projections);
            next.add(new Field(name, provenInlineLayout));
            return new Place(root, next);
        }

        public Place range(long from, long to, boolean provenContiguousLayout) {
            ArrayList<Projection> next = new ArrayList<>(projections);
            next.add(new Range(from, to, provenContiguousLayout));
            return new Place(root, next);
        }
    }

    public record Claim(Place place, Mode mode) {
        public Claim {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** A source-level admission claim with its precisely resolved referent type. */
    public record TypedClaim(Claim claim, Types.Type type) {
        public TypedClaim {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Validates the Send/Sync proof before reserving any access, using the
     * existing internal auto-trait checker. READ requires T:Sync; WRITE
     * requires T:Send. A passing proof still does NOT establish lifetime,
     * actor-domain authorization, pinned code, or task quiescence.
     */
    public Ticket admitTyped(List<TypedClaim> requested,
                             ThreadSafetyTraits.NominalProof nominalProof) {
        Objects.requireNonNull(nominalProof, "nominalProof");
        List<TypedClaim> typed = List.copyOf(Objects.requireNonNull(requested, "claims"));
        for (TypedClaim claim : typed) {
            ThreadSafetyTraits.Trait needed = claim.claim().mode() == Mode.READ
                    ? ThreadSafetyTraits.Trait.SYNC : ThreadSafetyTraits.Trait.SEND;
            if (!ThreadSafetyTraits.has(claim.type(), needed, nominalProof)) {
                throw new IllegalArgumentException("Task access requires proven " + needed);
            }
        }
        return admit(typed.stream().map(TypedClaim::claim).toList());
    }

    /** Opaque compiler-issued child identity. A cancelled Ticket is still live. */
    public static final class Ticket {
        private final long id;
        private Ticket(long id) { this.id = id; }
    }

    private static final class Child {
        final List<Claim> claims;
        boolean cancelRequested;
        Child(List<Claim> claims) { this.claims = claims; }
    }

    private record AllocationKey(String domain, long id) { }

    // Bounded compiler analysis: rejecting a huge scope is safer than unbounded
    // memory use or integer overflow. A future MIR implementation can shard.
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CLAIMS_PER_CHILD = 1024;
    private static final int MAX_TRACKED_ALLOCATIONS = 65536;

    private final Map<Ticket, Child> live = new LinkedHashMap<>();
    private final Map<AllocationKey, Long> knownGenerations = new HashMap<>();
    /** Single trusted allocation/trust domain per scope, including READ-only claims. */
    private String scopeDomain;
    private long nextId = 1;
    private boolean sealed;

    /**
     * True means locations *may* alias. An UNKNOWN proof never establishes
     * nonaliasing. The two operands must already have valid domain authority.
     */
    public static boolean mayAlias(Place a, Place b) {
        Objects.requireNonNull(a, "first place");
        Objects.requireNonNull(b, "second place");
        Root left = a.root();
        Root right = b.root();
        if (!left.domain().equals(right.domain())) return true; // no cross-domain permit
        if (left.allocationId() != right.allocationId()) {
            return !(left.uniqueAllocationProven() && right.uniqueAllocationProven());
        }
        // A stale generation must not be turned into a disjointness proof.
        if (left.generation() != right.generation()) return true;
        List<Projection> la = a.projections(), lb = b.projections();
        for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
            Projection ap = la.get(i), bp = lb.get(i);
            if (ap instanceof Field af && bp instanceof Field bf) {
                if (!af.name().equals(bf.name())) {
                    return !(af.disjointLayoutProven() && bf.disjointLayoutProven());
                }
                if (!af.disjointLayoutProven() || !bf.disjointLayoutProven()) return true;
                continue;
            }
            if (ap instanceof Range ar && bp instanceof Range br) {
                if ((ar.end() <= br.start() || br.end() <= ar.start())
                        && ar.disjointLayoutProven() && br.disjointLayoutProven()) {
                    return false;
                }
                if (ar.start() == br.start() && ar.end() == br.end()
                        && ar.disjointLayoutProven() && br.disjointLayoutProven()) continue;
                return true;
            }
            return true; // mixed/unknown projections: may alias
        }
        // Equal paths or one path containing the other: overlapping.
        return true;
    }

    public static boolean conflicts(Claim a, Claim b) {
        return (a.mode() == Mode.WRITE || b.mode() == Mode.WRITE)
                && mayAlias(a.place(), b.place());
    }

    /**
     * Conservatively reserves all child borrows, or none. The frontend must
     * verify Send/Sync and task scope lifetime *before* invoking this method.
     */
    public synchronized Ticket admit(List<Claim> requested) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        List<Claim> claims = List.copyOf(Objects.requireNonNull(requested, "claims"));
        if (live.size() >= MAX_CHILDREN || claims.size() > MAX_CLAIMS_PER_CHILD) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        Map<AllocationKey, Long> incomingGenerations = new HashMap<>();
        String requestedDomain = scopeDomain;
        for (Claim claim : claims) {
            Root root = claim.place().root();
            if (requestedDomain == null) requestedDomain = root.domain();
            if (!requestedDomain.equals(root.domain())) {
                throw new IllegalArgumentException("TaskScope cannot mix allocation/trust domains");
            }
            Objects.requireNonNull(claim, "claim");
            AllocationKey key = new AllocationKey(
                    claim.place().root().domain(), claim.place().root().allocationId());
            Long incoming = incomingGenerations.putIfAbsent(
                    key, claim.place().root().generation());
            if (incoming != null && incoming != claim.place().root().generation()) {
                throw new IllegalStateException("conflicting allocation generations in one Task");
            }
            Long established = knownGenerations.get(key);
            if (established != null && established != claim.place().root().generation()) {
                throw new IllegalStateException("stale or conflicting allocation generation");
            }
            for (Child other : live.values()) {
                for (Claim existing : other.claims) {
                    if (conflicts(claim, existing)) {
                        throw new IllegalArgumentException("overlapping Task READ/WRITE access");
                    }
                }
            }
        }
        for (int i = 0; i < claims.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (conflicts(claims.get(i), claims.get(j))) {
                    throw new IllegalArgumentException("overlapping claims inside one Task");
                }
            }
        }
        long newAllocations = incomingGenerations.keySet().stream()
                .filter(key -> !knownGenerations.containsKey(key)).count();
        if (newAllocations > MAX_TRACKED_ALLOCATIONS - knownGenerations.size()) {
            throw new IllegalStateException("TaskScope allocation tracking capacity exceeded");
        }
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("Task ticket overflow");
        Ticket ticket = new Ticket(nextId++);
        // Only now commit the scope domain, claims and allocation generations.
        scopeDomain = requestedDomain;
        live.put(ticket, new Child(claims));
        for (Claim claim : claims) {
            Root root = claim.place().root();
            knownGenerations.putIfAbsent(
                    new AllocationKey(root.domain(), root.allocationId()), root.generation());
        }
        return ticket;
    }

    /** Cancellation is an intent; a still-running Task continues to own loans. */
    public synchronized void requestCancel(Ticket ticket) {
        child(ticket).cancelRequested = true;
    }

    public synchronized boolean cancellationRequested(Ticket ticket) {
        return child(ticket).cancelRequested;
    }

    public synchronized boolean parentMayAccess(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        Root root = claim.place().root();
        if (scopeDomain == null || !scopeDomain.equals(root.domain())) return false;
        Long current = knownGenerations.get(new AllocationKey(root.domain(), root.allocationId()));
        if (current == null || current != root.generation()) return false;
        for (Child child : live.values()) {
            for (Claim held : child.claims) {
                if (conflicts(claim, held)) return false;
            }
        }
        return true;
    }

    /**
     * Called only after child execution, defers, FFI pins and cleanup are
     * physically quiescent. The ledger itself cannot prove runtime quiescence.
     */
    public synchronized void quiescent(Ticket ticket) {
        child(ticket); // validates this exact ticket belongs to this scope
        live.remove(ticket);
    }

    /**
     * Compiler-validated structural barrier: replace the layout generation
     * only after all child borrows of that allocation are physically drained.
     * Callers must independently prove owner authority and real quiescence.
     */
    public synchronized void structuralBarrier(Root oldRoot, long newGeneration) {
        Objects.requireNonNull(oldRoot, "oldRoot");
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        if (newGeneration <= oldRoot.generation()) {
            throw new IllegalArgumentException("generation must advance");
        }
        if (!oldRoot.domain().equals(scopeDomain)) {
            throw new IllegalArgumentException("structural barrier outside TaskScope domain");
        }
        AllocationKey key = new AllocationKey(oldRoot.domain(), oldRoot.allocationId());
        Long current = knownGenerations.get(key);
        if (current == null || current != oldRoot.generation()) {
            throw new IllegalStateException("stale structural generation");
        }
        for (Child child : live.values()) {
            for (Claim claim : child.claims) {
                // A different numeric allocation ID is NOT enough to prove
                // disjointness unless both roots have trusted uniqueness proofs.
                if (mayAlias(Place.whole(oldRoot), claim.place())) {
                    throw new IllegalStateException("cannot change layout during live Task borrow");
                }
            }
        }
        knownGenerations.put(key, newGeneration);
    }

    public synchronized void finishScope() {
        if (!live.isEmpty()) {
            throw new IllegalStateException("TaskScope cannot finish before child quiescence");
        }
        sealed = true;
    }

    public synchronized int activeChildren() { return live.size(); }

    private Child child(Ticket ticket) {
        Child result = live.get(Objects.requireNonNull(ticket, "ticket"));
        if (result == null) throw new IllegalArgumentException("stale or foreign Task ticket");
        return result;
    }
}
