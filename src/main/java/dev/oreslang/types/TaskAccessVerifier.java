package dev.oreslang.types;

import dev.oreslang.ast.Ast;

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
 * Cancellation does not release loans. Admission returns a one-shot physical
 * completion capability which trusted lowering binds to Task.getDone() or the
 * scheduler's onPhysicalExit callback; no raw ticket-release API exists.
 */
final class TaskAccessVerifier {
    private static final int MAX_LABEL_CHARS = 256;
    private static final int MAX_PROJECTION_DEPTH = 64;
    private static final java.util.Set<String> TRUST_DOMAINS = java.util.Set.of(
            "PROCESS", "ACTOR_SHARED", "ACTOR_PRIVATE", "UNTRUSTED");
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
                    || allocationId <= 0 || generation <= 0
                    || !TRUST_DOMAINS.contains(domain)) {
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

    /**
     * Coordinate units must be explicit for actual byte-layout projections.
     * Comparing byte offsets with array element indices as if they shared one
     * number line could falsely prove that two overlapping writes are disjoint.
     */
    public enum RangeUnit { ELEMENTS, BYTES }

    /** Half-open span tied to a single verified allocation, unit and layout. */
    public record Range(long start, long end, boolean disjointLayoutProven,
                        RangeUnit unit) implements Projection {
        public Range {
            Objects.requireNonNull(unit, "range coordinate unit");
            if (start < 0 || end <= start)
                throw new IllegalArgumentException("invalid half-open range");
        }
        /** Backward-compatible element-index API; never interpret as bytes. */
        public Range(long start, long end, boolean proven) {
            this(start, end, proven, RangeUnit.ELEMENTS);
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
            return rangeIn(from, to, provenContiguousLayout, RangeUnit.ELEMENTS);
        }

        public Place byteRange(long from, long to, boolean provenByteLayout) {
            return rangeIn(from, to, provenByteLayout, RangeUnit.BYTES);
        }

        private Place rangeIn(long from, long to, boolean provenLayout, RangeUnit unit) {
            ArrayList<Projection> next = new ArrayList<>(projections);
            next.add(new Range(from, to, provenLayout, unit));
            return new Place(root, next);
        }
    }

    public record Claim(Place place, Mode mode) {
        public Claim {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** A scoped-loan claim with its precisely resolved REFERENT type. */
    public record TypedClaim(Claim claim, Types.Type type) {
        public TypedClaim {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(type, "type");
            if (containsBorrowCapability(type)) {
                throw new IllegalArgumentException(
                        "nested borrowed referents require an explicit outlives proof");
            }
            // Keep erased-interface loans behind a proof separate from auto traits.
            // Future dyn I + Send/Sync must not silently mint concrete layout,
            // implementation identity or a scope-bound lifetime.
            if (Types.containsDynWitness(type)) {
                throw new IllegalArgumentException(
                        "scoped dyn referents require concrete implementation and lifetime proof");
            }
        }
    }

    /**
     * Trusted typed-place lookup from compiler MIR/place analysis. A caller
     * cannot gain a loan by pairing an unsafe place with a convenient type.
     */
    @FunctionalInterface
    public interface PlaceTypeProof {
        Types.Type resolvedReferent(Place place);
    }

    private static boolean containsBorrowCapability(Types.Type root) {
        record Pending(Types.Type type, int depth) { }
        java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();
        java.util.Set<Types.Type> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.push(new Pending(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            Types.Type type = current.type();
            if (type == null || current.depth() > 256 || ++nodes > 4096) return true;
            if (!seen.add(type)) continue;
            if (type instanceof Types.Borrow) return true;
            int next = current.depth() + 1;
            if (type instanceof Types.Named named) {
                for (Types.Type item : named.arguments()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Dyn dyn) {
                pending.push(new Pending(dyn.interfaceType(), next));
            } else if (type instanceof Types.ListType list) {
                pending.push(new Pending(list.element(), next));
            } else if (type instanceof Types.Tuple tuple) {
                for (Types.Type item : tuple.elements()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Record record) {
                for (Types.Type item : record.members().values()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Union union) {
                for (Types.Type item : union.options()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Function function) {
                for (Types.Type item : function.parameters()) pending.push(new Pending(item, next));
                pending.push(new Pending(function.result(), next));
            } else if (type instanceof Types.SelfType self) {
                pending.push(new Pending(self.bound(), next));
            } else if (type instanceof Types.Not not) {
                pending.push(new Pending(not.excluded(), next));
            }
        }
        return false;
    }

    private static boolean containsReadonlyRecordCapability(Types.Type root) {
        record Pending(Types.Type type, int depth) { }
        java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();
        java.util.Set<Types.Type> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.push(new Pending(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            Types.Type type = current.type();
            if (type == null || current.depth() > 256 || ++nodes > 4096) return true;
            if (!seen.add(type)) continue;
            if (type instanceof Types.Record record && record.readOnly()) return true;
            int next = current.depth() + 1;
            if (type instanceof Types.Named named) {
                for (Types.Type item : named.arguments()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Dyn dyn) {
                pending.push(new Pending(dyn.interfaceType(), next));
            } else if (type instanceof Types.ListType list) {
                pending.push(new Pending(list.element(), next));
            } else if (type instanceof Types.Tuple tuple) {
                for (Types.Type item : tuple.elements()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Record record) {
                for (Types.Type item : record.members().values()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Union union) {
                for (Types.Type item : union.options()) pending.push(new Pending(item, next));
            } else if (type instanceof Types.Function function) {
                for (Types.Type item : function.parameters()) pending.push(new Pending(item, next));
                pending.push(new Pending(function.result(), next));
            } else if (type instanceof Types.SelfType self) {
                pending.push(new Pending(self.bound(), next));
            } else if (type instanceof Types.Not not) {
                pending.push(new Pending(not.excluded(), next));
            }
        }
        return false;
    }

    /**
     * Scoped READ requires T:Sync; scoped WRITE requires T:Send. Neither route
     * calls isOwnedSend because a scoped loan is not a detached move.
     */
    public synchronized Admission admitScopedTyped(
            List<TypedClaim> requested,
            ThreadSafetyTraits.NominalProof nominalProof,
            PlaceTypeProof trustedPlaceTypes) {
        if (admissionInProgress) {
            throw new IllegalStateException("reentrant Task access admission");
        }
        admissionInProgress = true;
        try {
            Objects.requireNonNull(nominalProof, "nominalProof");
            Objects.requireNonNull(trustedPlaceTypes, "trusted place types");
            Objects.requireNonNull(requested, "claims");
            preflightAdmissionSize(requested.size());
            List<TypedClaim> typed = List.copyOf(requested);
            if (typed.isEmpty()) {
                throw new IllegalArgumentException(
                        "scoped Task access requires at least one loan claim");
            }

            Map<PlaceIdentity, Types.Type> pendingTypes = new HashMap<>();
            for (TypedClaim claim : typed) {
                Claim access = claim.claim();
                Types.Type actual = trustedPlaceTypes.resolvedReferent(access.place());
                if (actual == null || !actual.equals(claim.type())) {
                    throw new IllegalArgumentException(
                            "Task claim referent type does not match compiler-resolved place type");
                }
                recordPlaceTypeCandidate(
                        access.place(), actual, pendingTypes);
                validateScopedResolvedClaim(access, actual, nominalProof);
            }
            validateNewPlaceCapacity(pendingTypes);
            Ticket ticket = admitClaims(
                    typed.stream().map(TypedClaim::claim).toList());
            knownPlaceTypes.putAll(pendingTypes);
            return new Admission(ticket, new PhysicalCompletion(this, ticket));
        } finally {
            admissionInProgress = false;
        }
    }

    private void preflightAdmissionSize(int requestedCount) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        if (live.size() >= MAX_CHILDREN
                || requestedCount > MAX_CLAIMS_PER_CHILD
                || requestedCount > MAX_LIVE_CLAIMS - liveClaimCount) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        long potentialChecks = (long) requestedCount * liveClaimCount
                + (long) requestedCount * (requestedCount - 1) / 2;
        if (potentialChecks > MAX_PAIRWISE_CHECKS_PER_ADMISSION) {
            throw new IllegalStateException(
                    "TaskScope proof-comparison budget exceeded");
        }
    }

    private void recordPlaceTypeCandidate(
            Place place,
            Types.Type actual,
            Map<PlaceIdentity, Types.Type> pendingTypes) {
        PlaceIdentity identity = PlaceIdentity.of(place);
        Types.Type previous = knownPlaceTypes.get(identity);
        Types.Type duplicate = pendingTypes.putIfAbsent(identity, actual);
        if ((previous != null && !previous.equals(actual))
                || (duplicate != null && !duplicate.equals(actual))) {
            throw new IllegalStateException(
                    "Task place changed type without a generation barrier");
        }
    }

    private void validateNewPlaceCapacity(
            Map<PlaceIdentity, Types.Type> pendingTypes) {
        long newPlaces = pendingTypes.keySet().stream()
                .filter(place -> !knownPlaceTypes.containsKey(place))
                .count();
        if (newPlaces > MAX_TRACKED_PLACES - knownPlaceTypes.size()) {
            throw new IllegalStateException(
                    "Task place type tracking capacity exceeded");
        }
    }

    private static void validateScopedResolvedClaim(
            Claim claim,
            Types.Type type,
            ThreadSafetyTraits.NominalProof nominalProof) {
        if (claim.mode() == Mode.WRITE && containsReadonlyRecordCapability(type)) {
            throw new IllegalArgumentException(
                    "scoped Task WRITE requires mutable referent authority");
        }
        ThreadSafetyTraits.Trait needed = claim.mode() == Mode.READ
                ? ThreadSafetyTraits.Trait.SYNC : ThreadSafetyTraits.Trait.SEND;
        if (!ThreadSafetyTraits.has(type, needed, nominalProof)) {
            throw new IllegalArgumentException(
                    "scoped Task access requires proven " + needed);
        }
    }

    /**
     * A proof that the source AST and resolved compiler type denote the SAME
     * explicitly supported built-in payload. Raw guest values cannot supply
     * this claim: caller must have already resolved the trusted place root,
     * field layout and ownership identity in compiler-owned state.
     */
    public record CoherentTypedClaim(Claim claim, Ast.TypeRef sourceType,
                                     Types.Type resolvedType) {
        public CoherentTypedClaim {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(resolvedType, "resolvedType");
        }
    }

    /**
     * Atomic, dual-representation admission for supported source Task
     * captures and future scoped region loans. A passing type proof is still
     * NOT physical quiescence, actor authorization or executable nb lowering.
     * Unsupported nominal/record layouts deny until a trusted resolved
     * identity + field-layout witness exists.
     */
    public synchronized Admission admitCoherentTyped(
            List<CoherentTypedClaim> requested,
            SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof resolvedNominals,
            PlaceTypeProof trustedPlaceTypes) {
        if (admissionInProgress) {
            throw new IllegalStateException("reentrant Task access admission");
        }
        admissionInProgress = true;
        try {
            Objects.requireNonNull(sourceProof, "sourceProof");
            Objects.requireNonNull(resolvedNominals, "resolvedNominals");
            Objects.requireNonNull(trustedPlaceTypes, "trusted place types");
            Objects.requireNonNull(requested, "claims");
            preflightAdmissionSize(requested.size());
            List<CoherentTypedClaim> typed = List.copyOf(requested);
            if (typed.isEmpty()) {
                throw new IllegalArgumentException(
                        "empty scoped Task footprint requires a compiler-proven pure effect");
            }

            Map<PlaceIdentity, Types.Type> pendingTypes = new HashMap<>();
            for (CoherentTypedClaim entry : typed) {
                Types.Type actual =
                        trustedPlaceTypes.resolvedReferent(entry.claim().place());
                if (actual == null || !actual.equals(entry.resolvedType())) {
                    throw new IllegalArgumentException(
                            "Task claim referent type does not match compiler-resolved place type");
                }
                recordPlaceTypeCandidate(
                        entry.claim().place(), actual, pendingTypes);
                if (!corresponds(entry.sourceType(), actual, 0)) {
                    throw new IllegalArgumentException(
                            "source and lowered Task types lack a matching trusted layout");
                }
                if (containsBorrowCapability(actual)) {
                    throw new IllegalArgumentException(
                            "scoped Task referent contains a nested borrow without outlives proof");
                }
                if (Types.containsDynWitness(actual)) {
                    throw new IllegalArgumentException(
                            "scoped dyn referents require concrete implementation and lifetime proof");
                }
                boolean send = entry.claim().mode() == Mode.WRITE;
                boolean sourceAllowed = send
                        ? sourceProof.isSend(entry.sourceType())
                        : sourceProof.isSync(entry.sourceType());
                ThreadSafetyTraits.Trait needed = send
                        ? ThreadSafetyTraits.Trait.SEND
                        : ThreadSafetyTraits.Trait.SYNC;
                if (!sourceAllowed) {
                    throw new IllegalArgumentException(
                            "Task source preflight lacks matching "
                                    + needed + " candidate");
                }
                validateScopedResolvedClaim(
                        entry.claim(), actual, resolvedNominals);
            }
            validateNewPlaceCapacity(pendingTypes);
            Ticket ticket = admitClaims(
                    typed.stream().map(CoherentTypedClaim::claim).toList());
            knownPlaceTypes.putAll(pendingTypes);
            return new Admission(ticket, new PhysicalCompletion(this, ticket));
        } finally {
            admissionInProgress = false;
        }
    }

    /** Intentionally small trusted ABI subset; no nominal name guessing. */
    private static boolean corresponds(Ast.TypeRef surface, Types.Type lowered,
                                        int depth) {
        if (surface == null || lowered == null || depth >= 64
                || surface.inferArguments() || surface.isComplement()
                || surface.isUnion() || surface.isTupleType()) return false;
        // A lexical or mutable borrow requires a compiler-minted lifetime,
        // provenance and physical child-exit witness. This bridge receives
        // only type names, so even a structurally equal &T / &mut T must deny.
        if (surface.isBorrow() || lowered instanceof Types.Borrow) return false;
        List<Ast.TypeRef> args = surface.arguments();
        if (args.isEmpty()) {
            return switch (surface.name()) {
                case "int" -> lowered == Types.Primitive.INT;
                case "bool" -> lowered == Types.Primitive.BOOL;
                case "float" -> lowered == Types.Primitive.FLOAT;
                case "string" -> lowered == Types.Primitive.STRING;
                case "decimal" -> lowered == Types.Primitive.DECIMAL;
                case "complex" -> lowered == Types.Primitive.COMPLEX;
                default -> false; // nominal, literal, native or unproven builtin
            };
        }
        if (args.size() == 1
                && (surface.name().equals("Array") || surface.name().equals("List"))) {
            return lowered instanceof Types.ListType list
                    && corresponds(args.getFirst(), list.element(), depth + 1);
        }
        // Types.Named carries a spelling, not a checked intrinsic identity.
        // Comparing "Option"/"Result" strings would allow an imported type
        // with that spelling to impersonate the built-in. This code has no
        // trusted nominal resolution witness; fail closed until MIR provides
        // that immutable witness rather than accepting name equality.
        return false;
    }

    /** Opaque compiler admission; only its physical completion can retire the loan. */
    public static final class Admission {
        private final Ticket ticket;
        private final PhysicalCompletion completion;

        private Admission(Ticket ticket, PhysicalCompletion completion) {
            this.ticket = ticket;
            this.completion = completion;
        }

        public Ticket ticket() { return ticket; }
        public PhysicalCompletion completion() { return completion; }
    }

    /**
     * One-shot capability intended to be passed as the trusted runtime's
     * onPhysicalExit callback. Result settlement/cancellation cannot invoke it.
     */
    public static final class PhysicalCompletion {
        private final TaskAccessVerifier owner;
        private final Ticket ticket;
        private boolean released;

        private PhysicalCompletion(TaskAccessVerifier owner, Ticket ticket) {
            this.owner = owner;
            this.ticket = ticket;
        }

        public synchronized void releaseAfterPhysicalQuiescence() {
            if (released) {
                throw new IllegalStateException(
                        "Task physical completion already consumed");
            }
            owner.releaseAfterPhysicalQuiescence(ticket);
            released = true;
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

    private record ProjectionIdentity(
            String kind, String name, long start, long end, String unit) { }

    private record PlaceIdentity(
            AllocationKey allocation,
            long generation,
            List<ProjectionIdentity> projections) {
        static PlaceIdentity of(Place place) {
            Root root = place.root();
            ArrayList<ProjectionIdentity> path =
                    new ArrayList<>(place.projections().size());
            for (Projection projection : place.projections()) {
                if (projection instanceof Field field) {
                    path.add(new ProjectionIdentity(
                            "field", field.name(), 0, 0, ""));
                } else if (projection instanceof Range range) {
                    path.add(new ProjectionIdentity(
                            "range", "", range.start(), range.end(),
                            range.unit().name()));
                } else {
                    throw new IllegalArgumentException(
                            "unverified Task place projection");
                }
            }
            return new PlaceIdentity(
                    new AllocationKey(
                            root.domain(), root.ownerId(), root.allocationId()),
                    root.generation(),
                    List.copyOf(path));
        }
    }

    // Bounded compiler analysis: rejecting a huge scope is safer than unbounded
    // memory use or integer overflow. A future MIR implementation can shard.
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CLAIMS_PER_CHILD = 1024;
    private static final int MAX_LIVE_CLAIMS = 8192;
    private static final long MAX_PAIRWISE_CHECKS_PER_ADMISSION = 2_000_000L;
    private static final int MAX_TRACKED_ALLOCATIONS = 65536;
    private static final int MAX_TRACKED_PLACES = 65536;

    private final Map<Ticket, Child> live = new LinkedHashMap<>();
    private final Map<AllocationKey, Long> knownGenerations = new HashMap<>();
    private final Map<AllocationKey, Boolean> knownUniqueness = new HashMap<>();
    private final Map<PlaceIdentity, Types.Type> knownPlaceTypes = new HashMap<>();
    /** Single trusted allocation/trust domain per scope, including READ-only claims. */
    private ScopeKey scopeDomain;
    private long nextId = 1;
    private int liveClaimCount;
    private boolean sealed;
    /** Java monitor locking is reentrant; proof callbacks may not mutate this ledger. */
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
        // Projection-level separation cannot discharge aliases outside this
        // scope unless the backing allocation itself is compiler-proven unique.
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
                // Different coordinate systems are incomparable without a
                // trusted element-width conversion / physical layout proof.
                if (ar.unit() != br.unit()) return true;
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
     * Conservatively reserves all child borrows, or none. The frontend must
     * verify Send/Sync and task scope lifetime *before* invoking this method.
     */
    private synchronized Ticket admitClaims(List<Claim> requested) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        List<Claim> claims = List.copyOf(Objects.requireNonNull(requested, "claims"));
        if (claims.isEmpty()) {
            throw new IllegalArgumentException(
                    "scoped Task access requires at least one loan claim");
        }
        if (live.size() >= MAX_CHILDREN
                || claims.size() > MAX_CLAIMS_PER_CHILD
                || claims.size() > MAX_LIVE_CLAIMS - liveClaimCount) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        long claimCount = claims.size();
        long comparisons = claimCount * liveClaimCount
                + claimCount * (claimCount - 1) / 2;
        if (comparisons > MAX_PAIRWISE_CHECKS_PER_ADMISSION) {
            throw new IllegalStateException(
                    "TaskScope proof-comparison budget exceeded");
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
            if (established != null
                    && established != claim.place().root().generation()) {
                throw new IllegalStateException(
                        "stale or conflicting allocation generation");
            }
            Boolean sameBatchUniqueness = incomingUniqueness.putIfAbsent(
                    key, root.uniqueAllocationProven());
            Boolean knownProof = knownUniqueness.get(key);
            if ((sameBatchUniqueness != null
                    && sameBatchUniqueness != root.uniqueAllocationProven())
                    || (knownProof != null
                    && knownProof != root.uniqueAllocationProven())) {
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
            knownUniqueness.putIfAbsent(
                    key, root.uniqueAllocationProven());
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
    private synchronized void releaseAfterPhysicalQuiescence(Ticket ticket) {
        rejectAdmissionReentrancy();
        Child completed = child(ticket);
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
            throw new IllegalStateException(
                    "structural barrier has inconsistent owner proof");
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
        knownPlaceTypes.keySet().removeIf(
                place -> place.allocation().equals(key));
    }

    /** Stop new admissions without releasing any live loan. */
    public synchronized void seal() {
        rejectAdmissionReentrancy();
        sealed = true;
    }

    public synchronized void finishScope() {
        rejectAdmissionReentrancy();
        if (!sealed) {
            throw new IllegalStateException(
                    "TaskScope must be sealed before provenance retirement");
        }
        if (!live.isEmpty() || liveClaimCount != 0) {
            throw new IllegalStateException(
                    "TaskScope cannot finish before physical child quiescence");
        }
        knownGenerations.clear();
        knownUniqueness.clear();
        knownPlaceTypes.clear();
        scopeDomain = null;
    }

    public synchronized int activeChildren() { return live.size(); }
    public synchronized int activeClaims() { return liveClaimCount; }

    private void rejectAdmissionReentrancy() {
        if (admissionInProgress) {
            throw new IllegalStateException(
                    "Task proof callback cannot mutate its own ledger");
        }
    }

    private Child child(Ticket ticket) {
        Child result = live.get(Objects.requireNonNull(ticket, "ticket"));
        if (result == null) throw new IllegalArgumentException("stale or foreign Task ticket");
        return result;
    }
}
