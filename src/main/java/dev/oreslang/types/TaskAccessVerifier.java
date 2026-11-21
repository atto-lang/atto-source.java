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
    private static final int MAX_LABEL_CHARS = 256;
    private static final int MAX_PROJECTION_DEPTH = 64;
    public enum Mode { READ, WRITE }

    /**
     * Canonical allocation provenance from the borrow checker. Distinct IDs
     * prove nonaliasing only when BOTH roots have compiler-verified uniqueness.
     * A binding name, OS thread ID or value-equal user token cannot supply it.
     */
    public record Root(String domain, String ownerId, long allocationId, long generation,
                       boolean uniqueAllocationProven) {
        public Root {
            if (domain == null || domain.isBlank() || ownerId == null || ownerId.isBlank()
                    || domain.length() > MAX_LABEL_CHARS || ownerId.length() > MAX_LABEL_CHARS
                    || allocationId <= 0 || generation <= 0) {
                throw new IllegalArgumentException("invalid allocation provenance");
            }
            if (!domain.equals("PROCESS") && ownerId.equals(domain)) {
                // A category such as ACTOR_PRIVATE or UNTRUSTED is not an
                // isolation identity. Its per-actor/tenant allocation owner and
                // execution epoch must be proven by the compiler.
                throw new IllegalArgumentException(
                        "non-process allocations require a distinct owner identity");
            }
        }

        /**
         * Compatibility only for process-local compiler storage. Actor and
         * untrusted allocations must supply owner identity explicitly.
         */
        public Root(String domain, long allocationId, long generation,
                    boolean uniqueAllocationProven) {
            this(domain, domain, allocationId, generation, uniqueAllocationProven);
        }
    }

    public sealed interface Projection permits Field, Range { }

    /** Verified inline field storage, not a pointer/FFI alias to arbitrary memory. */
    public record Field(String name, boolean disjointLayoutProven) implements Projection {
        public Field {
            if (name == null || name.isBlank() || name.length() > MAX_LABEL_CHARS) {
                throw new IllegalArgumentException("invalid field name");
            }
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
            Objects.requireNonNull(projections, "projections");
            if (projections.size() > MAX_PROJECTION_DEPTH) {
                throw new IllegalArgumentException("Task place projection depth exceeded");
            }
            projections = List.copyOf(projections);
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
     * Lookup supplied by the trusted typed-place analysis, NOT from a
     * user-written type annotation. The result must identify the referent at
     * the *same projection path* and allocation generation as the claim.
     */
    @FunctionalInterface
    public interface PlaceTypeProof {
        Types.Type resolvedReferent(Place place);
    }

    /**
     * Validate both the actual resolved referent type and Send/Sync proof
     * before reserving any access. A caller may not claim e.g. an unsafe
     * native handle has primitive type just to obtain a Send proof.
     *
     * <p>This remains a compiler-only ledger. It does not mint place/owner
     * authority, grant actor-domain access, prove lifetime, or authenticate
     * actual runtime quiescence.
     */
    public synchronized Ticket admitTyped(List<TypedClaim> requested,
                             ThreadSafetyTraits.NominalProof nominalProof,
                             PlaceTypeProof trustedPlaceTypes) {
        if (admissionInProgress) {
            throw new IllegalStateException("reentrant Task access admission");
        }
        admissionInProgress = true;
        try {
        Objects.requireNonNull(nominalProof, "nominalProof");
        Objects.requireNonNull(trustedPlaceTypes, "trustedPlaceTypes");
        Objects.requireNonNull(requested, "claims");
        // Reject oversized or sealed submissions before evaluating any
        // compiler-supplied resolver/trait callback or copying large lists.
        // reserveVerified repeats these checks at the actual commit boundary.
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        int requestedCount = requested.size();
        if (live.size() >= MAX_CHILDREN || requestedCount > MAX_CLAIMS_PER_CHILD
                || requestedCount > MAX_LIVE_CLAIMS - liveClaimCount) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        long potentialChecks = (long) requestedCount * liveClaimCount
                + (long) requestedCount * (requestedCount - 1) / 2;
        if (potentialChecks > MAX_PAIRWISE_CHECKS_PER_ADMISSION) {
            throw new IllegalStateException("TaskScope proof-comparison budget exceeded");
        }
        List<TypedClaim> typed = List.copyOf(requested);
        Map<PlaceIdentity, Types.Type> pendingTypes = new HashMap<>();
        for (TypedClaim claim : typed) {
            Types.Type actual = trustedPlaceTypes.resolvedReferent(claim.claim().place());
            if (actual == null || !actual.equals(claim.type())) {
                throw new IllegalArgumentException(
                        "Task claim referent type does not match resolved place type");
            }
            Place place = claim.claim().place();
            // Layout-proof bits are evidence, not storage identity: toggling
            // their boolean values cannot erase an earlier type witness.
            PlaceIdentity identity = PlaceIdentity.of(place);
            Types.Type previous = knownPlaceTypes.get(identity);
            Types.Type duplicate = pendingTypes.putIfAbsent(identity, actual);
            if ((previous != null && !previous.equals(actual))
                    || (duplicate != null && !duplicate.equals(actual))) {
                throw new IllegalStateException(
                        "Task place changed type without a generation barrier");
            }
            ThreadSafetyTraits.Trait needed = claim.claim().mode() == Mode.READ
                    ? ThreadSafetyTraits.Trait.SYNC : ThreadSafetyTraits.Trait.SEND;
            if (!ThreadSafetyTraits.has(actual, needed, nominalProof)) {
                throw new IllegalArgumentException("Task access requires proven " + needed);
            }
        }
        long newPlaces = pendingTypes.keySet().stream()
                .filter(place -> !knownPlaceTypes.containsKey(place)).count();
        if (newPlaces > MAX_TRACKED_PLACES - knownPlaceTypes.size()) {
            throw new IllegalStateException("Task place type tracking capacity exceeded");
        }
        Ticket admitted = reserveVerified(typed.stream().map(TypedClaim::claim).toList());
        knownPlaceTypes.putAll(pendingTypes);
        return admitted;
        } finally {
            admissionInProgress = false;
        }
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

    private record AllocationKey(String domain, String ownerId, long id) { }
    private record ScopeKey(String domain, String ownerId) { }

    /**
     * Equality of physical place does NOT include evidence flags. It still
     * includes the allocation generation, so a proven structural barrier
     * gives a fresh type identity without allowing an ABA-style overwrite.
     */
    private record ProjectionIdentity(String kind, String name, long start, long end) { }
    private record PlaceIdentity(AllocationKey allocation, long generation,
                                 List<ProjectionIdentity> projections) {
        static PlaceIdentity of(Place place) {
            Root root = place.root();
            ArrayList<ProjectionIdentity> path = new ArrayList<>(place.projections().size());
            for (Projection projection : place.projections()) {
                if (projection instanceof Field field) {
                    path.add(new ProjectionIdentity("field", field.name(), 0, 0));
                } else if (projection instanceof Range range) {
                    path.add(new ProjectionIdentity("range", "", range.start(), range.end()));
                } else {
                    throw new IllegalArgumentException("unverified Task place projection");
                }
            }
            return new PlaceIdentity(
                    new AllocationKey(root.domain(), root.ownerId(), root.allocationId()),
                    root.generation(), List.copyOf(path));
        }
    }

    // Bounded compiler analysis: rejecting a huge scope is safer than unbounded
    // memory use or integer overflow. A future MIR implementation can shard.
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CLAIMS_PER_CHILD = 1024;
    private static final int MAX_LIVE_CLAIMS = 8192;
    // Avoid quadratic compile-time DoS: a single admission cannot demand
    // more than two million pairwise compatibility decisions.
    private static final long MAX_PAIRWISE_CHECKS_PER_ADMISSION = 2_000_000L;
    private static final int MAX_TRACKED_ALLOCATIONS = 65536;
    private static final int MAX_TRACKED_PLACES = 65536;

    private final Map<Ticket, Child> live = new LinkedHashMap<>();
    private final Map<AllocationKey, Long> knownGenerations = new HashMap<>();
    /** A claimed allocation identity may not change its uniqueness proof. */
    private final Map<AllocationKey, Boolean> knownUniqueness = new HashMap<>();
    /** Same physical place/generation cannot change type by changing proof flags. */
    private final Map<PlaceIdentity, Types.Type> knownPlaceTypes = new HashMap<>();
    /** Single trusted allocation/trust domain per scope, including READ-only claims. */
    private ScopeKey scopeDomain;
    private long nextId = 1;
    private int liveClaimCount;
    private boolean sealed;
    /** Java monitor locking is reentrant; proof callbacks must not mutate the ledger. */
    private boolean admissionInProgress;

    /**
     * True means locations *may* alias. An UNKNOWN proof never establishes
     * nonaliasing. The two operands must already have valid domain authority.
     */
    public static boolean mayAlias(Place a, Place b) {
        Objects.requireNonNull(a, "first place");
        Objects.requireNonNull(b, "second place");
        Root left = a.root();
        Root right = b.root();
        if (!left.domain().equals(right.domain())
                || !left.ownerId().equals(right.ownerId())) return true; // no cross-owner permit
        if (left.allocationId() != right.allocationId()) {
            return !(left.uniqueAllocationProven() && right.uniqueAllocationProven());
        }
        // A stale generation must not be turned into a disjointness proof.
        if (left.generation() != right.generation()) return true;
        // For the same numeric allocation, a syntactic field/element split
        // cannot discharge an unknown backing-store alias. Both roots must
        // have a trusted unique-allocation witness before any projection
        // may be used to conclude non-aliasing.
        if (!left.uniqueAllocationProven() || !right.uniqueAllocationProven()) {
            return true;
        }
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
        Objects.requireNonNull(a, "first claim");
        Objects.requireNonNull(b, "second claim");
        // A read/read pair is compatible only *within* one admitted domain.
        // Do not let a direct caller of conflicts() bypass scope admission.
        if (!a.place().root().domain().equals(b.place().root().domain())
                || !a.place().root().ownerId().equals(b.place().root().ownerId())) return true;
        return (a.mode() == Mode.WRITE || b.mode() == Mode.WRITE)
                && mayAlias(a.place(), b.place());
    }

    /**
     * Internal reservation step, reachable only after admitTyped validated
     * the trusted resolved-place type and transitive Send/Sync proof.
     * Scope/lifetime checks must still be validated by the compiler.
     */
    private synchronized Ticket reserveVerified(List<Claim> requested) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        List<Claim> claims = List.copyOf(Objects.requireNonNull(requested, "claims"));
        if (live.size() >= MAX_CHILDREN || claims.size() > MAX_CLAIMS_PER_CHILD
                || claims.size() > MAX_LIVE_CLAIMS - liveClaimCount) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        long claimCount = claims.size();
        long comparisons = claimCount * liveClaimCount
                + claimCount * (claimCount - 1) / 2;
        if (comparisons > MAX_PAIRWISE_CHECKS_PER_ADMISSION) {
            throw new IllegalStateException("TaskScope proof-comparison budget exceeded");
        }
        Map<AllocationKey, Long> incomingGenerations = new HashMap<>();
        Map<AllocationKey, Boolean> incomingUniqueness = new HashMap<>();
        ScopeKey requestedDomain = scopeDomain;
        for (Claim claim : claims) {
            Root root = claim.place().root();
            ScopeKey claimScope = new ScopeKey(root.domain(), root.ownerId());
            if (requestedDomain == null) requestedDomain = claimScope;
            if (!requestedDomain.equals(claimScope)) {
                throw new IllegalArgumentException("TaskScope cannot mix allocation owner identities");
            }
            Objects.requireNonNull(claim, "claim");
            AllocationKey key = new AllocationKey(root.domain(), root.ownerId(), root.allocationId());
            Long incoming = incomingGenerations.putIfAbsent(
                    key, claim.place().root().generation());
            if (incoming != null && incoming != claim.place().root().generation()) {
                throw new IllegalStateException("conflicting allocation generations in one Task");
            }
            Long established = knownGenerations.get(key);
            if (established != null && established != claim.place().root().generation()) {
                throw new IllegalStateException("stale or conflicting allocation generation");
            }
            Boolean sameBatchUniqueness = incomingUniqueness.putIfAbsent(
                    key, root.uniqueAllocationProven());
            Boolean knownProof = knownUniqueness.get(key);
            if ((sameBatchUniqueness != null
                    && sameBatchUniqueness != root.uniqueAllocationProven())
                    || (knownProof != null && knownProof != root.uniqueAllocationProven())) {
                throw new IllegalStateException(
                        "allocation identity changed its uniqueness proof");
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
        liveClaimCount += claims.size();
        for (Claim claim : claims) {
            Root root = claim.place().root();
            AllocationKey key = new AllocationKey(
                    root.domain(), root.ownerId(), root.allocationId());
            knownGenerations.putIfAbsent(key, root.generation());
            knownUniqueness.putIfAbsent(key, root.uniqueAllocationProven());
        }
        return ticket;
    }

    /** Cancellation is an intent; a still-running Task continues to own loans. */
    public synchronized void requestCancel(Ticket ticket) {
        rejectAdmissionReentrancy();
        child(ticket).cancelRequested = true;
    }

    public synchronized boolean cancellationRequested(Ticket ticket) {
        return child(ticket).cancelRequested;
    }

    public synchronized boolean parentMayAccess(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (sealed) return false; // scope teardown never grants fresh authority
        Root root = claim.place().root();
        if (scopeDomain == null || !scopeDomain.equals(
                new ScopeKey(root.domain(), root.ownerId()))) return false;
        Long current = knownGenerations.get(new AllocationKey(root.domain(), root.ownerId(), root.allocationId()));
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
        rejectAdmissionReentrancy();
        Child completed = child(ticket); // validates this exact ticket belongs to this scope
        live.remove(ticket);
        liveClaimCount -= completed.claims.size();
    }

    /**
     * Compiler-validated structural barrier: replace the layout generation
     * only after all child borrows of that allocation are physically drained.
     * Callers must independently prove owner authority and real quiescence.
     */
    public synchronized void structuralBarrier(Root oldRoot, long newGeneration) {
        rejectAdmissionReentrancy();
        Objects.requireNonNull(oldRoot, "oldRoot");
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        if (newGeneration <= oldRoot.generation()) {
            throw new IllegalArgumentException("generation must advance");
        }
        if (!new ScopeKey(oldRoot.domain(), oldRoot.ownerId()).equals(scopeDomain)) {
            throw new IllegalArgumentException("structural barrier outside TaskScope domain");
        }
        AllocationKey key = new AllocationKey(oldRoot.domain(), oldRoot.ownerId(), oldRoot.allocationId());
        Long current = knownGenerations.get(key);
        if (current == null || current != oldRoot.generation()) {
            throw new IllegalStateException("stale structural generation");
        }
        Boolean witnessedUnique = knownUniqueness.get(key);
        if (witnessedUnique == null
                || witnessedUnique != oldRoot.uniqueAllocationProven()) {
            throw new IllegalStateException("structural barrier has inconsistent owner proof");
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
        // Old-generation type witnesses cannot be reused after a checked
        // reallocation/layout transition. New claims must re-resolve types.
        knownPlaceTypes.keySet().removeIf(place -> place.allocation().equals(key));
    }

    public synchronized void finishScope() {
        rejectAdmissionReentrancy();
        if (!live.isEmpty() || liveClaimCount != 0) {
            throw new IllegalStateException("TaskScope cannot finish before child quiescence");
        }
        sealed = true;
        // No source permission is valid after scope teardown; retire
        // provenance bookkeeping rather than retaining every old allocation.
        knownGenerations.clear();
        knownUniqueness.clear();
        knownPlaceTypes.clear();
        scopeDomain = null;
    }

    public synchronized int activeChildren() { return live.size(); }
    public synchronized int activeClaims() { return liveClaimCount; }

    private void rejectAdmissionReentrancy() {
        if (admissionInProgress) {
            throw new IllegalStateException("Task proof callback cannot mutate its own ledger");
        }
    }

    private Child child(Ticket ticket) {
        Child result = live.get(Objects.requireNonNull(ticket, "ticket"));
        if (result == null) throw new IllegalArgumentException("stale or foreign Task ticket");
        return result;
    }
}
