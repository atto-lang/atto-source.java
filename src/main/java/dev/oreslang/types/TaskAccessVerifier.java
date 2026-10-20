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
    public synchronized Ticket admitTyped(List<TypedClaim> requested,
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
    public synchronized Ticket admitCoherentTyped(
            List<CoherentTypedClaim> requested, SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof resolvedNominals) {
        Objects.requireNonNull(sourceProof, "sourceProof");
        Objects.requireNonNull(resolvedNominals, "resolvedNominals");
        List<CoherentTypedClaim> typed = List.copyOf(
                Objects.requireNonNull(requested, "claims"));
        // An empty list is NOT evidence of a pure child: unchecked FFI,
        // global effects and unknown aliases require a separate effect proof.
        if (typed.isEmpty()) {
            throw new IllegalArgumentException(
                    "empty scoped Task footprint requires a compiler-proven pure effect");
        }
        for (CoherentTypedClaim entry : typed) {
            if (!corresponds(entry.sourceType(), entry.resolvedType(), 0)) {
                throw new IllegalArgumentException(
                        "source and lowered Task types lack a matching trusted layout");
            }
            boolean send = entry.claim().mode() == Mode.WRITE;
            boolean sourceAllowed = send ? sourceProof.isSend(entry.sourceType())
                    : sourceProof.isSync(entry.sourceType());
            ThreadSafetyTraits.Trait needed = send
                    ? ThreadSafetyTraits.Trait.SEND : ThreadSafetyTraits.Trait.SYNC;
            if (!sourceAllowed || !ThreadSafetyTraits.has(
                    entry.resolvedType(), needed, resolvedNominals)) {
                throw new IllegalArgumentException(
                        "Task access lacks matching source/resolved " + needed + " proof");
            }
        }
        // Reuses the SAME ledger and all-or-nothing alias/generation rules.
        return admit(typed.stream().map(CoherentTypedClaim::claim).toList());
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

    /** Opaque compiler-issued child identity. A cancelled Ticket is still live. */
    public static final class Ticket {
        private final Object scopeEpoch;
        private final long id;

        private Ticket(Object scopeEpoch, long id) {
            this.scopeEpoch = Objects.requireNonNull(scopeEpoch, "scopeEpoch");
            this.id = id;
        }
    }

    private static final class Child {
        final List<Claim> claims;
        boolean cancelRequested;
        Child(List<Claim> claims) { this.claims = claims; }
    }

    private record AllocationKey(String domain, String ownerId, long id) { }
    private record ScopeKey(String domain, String ownerId) { }

    // Bounded compiler analysis: rejecting a huge scope is safer than unbounded
    // memory use or integer overflow. A future MIR implementation can shard.
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CLAIMS_PER_CHILD = 1024;
    private static final int MAX_TRACKED_ALLOCATIONS = 65536;

    /** Identity-only epoch for this exact verifier lifetime. */
    private final Object scopeEpoch = new Object();
    private final Map<Ticket, Child> live = new LinkedHashMap<>();
    private final Map<AllocationKey, Long> knownGenerations = new HashMap<>();
    /** Single trusted allocation/trust domain per scope, including READ-only claims. */
    private ScopeKey scopeDomain;
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
        if (!left.domain().equals(right.domain())
                || !left.ownerId().equals(right.ownerId())) return true; // no cross-owner permit
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
    public synchronized Ticket admit(List<Claim> requested) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        List<Claim> claims = List.copyOf(Objects.requireNonNull(requested, "claims"));
        if (live.size() >= MAX_CHILDREN || claims.size() > MAX_CLAIMS_PER_CHILD) {
            throw new IllegalStateException("TaskScope access capacity exceeded");
        }
        Map<AllocationKey, Long> incomingGenerations = new HashMap<>();
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
        Ticket ticket = new Ticket(scopeEpoch, nextId++);
        // Only now commit the scope domain, claims and allocation generations.
        scopeDomain = requestedDomain;
        live.put(ticket, new Child(claims));
        for (Claim claim : claims) {
            Root root = claim.place().root();
            knownGenerations.putIfAbsent(
                    new AllocationKey(root.domain(), root.ownerId(), root.allocationId()), root.generation());
        }
        return ticket;
    }

    /*
     * Runtime-bound admissions intentionally have no raw Claim-only mint.
     * Executable lowering must pass the coherent source/resolved type proof
     * below in addition to alias and generation checks.
     */
    TaskLoanAdmission admitCoherentTypedRuntimeBound(
            List<CoherentTypedClaim> requested, SendSyncTraits sourceProof,
            ThreadSafetyTraits.NominalProof resolvedNominals) {
        return new TaskLoanAdmission(
                this, admitCoherentTyped(requested, sourceProof, resolvedNominals));
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
     * Runtime attestation that scheduler admission failed before executable
     * child registration. This is distinct from physical child retirement.
     */
    synchronized void retireNeverStarted(Ticket ticket) {
        child(ticket);
        live.remove(ticket);
    }

    /**
     * Called only after child execution, defers, FFI pins and cleanup are
     * physically quiescent. The ledger itself cannot prove runtime quiescence.
     */
    synchronized void retireAfterPhysicalQuiescence(Ticket ticket) {
        child(ticket);
        live.remove(ticket);
    }

    /**
     * Compatibility shim for older compiler-only tests. New runtime lowering
     * must use TaskLoanAdmission so a caller cannot choose the lifecycle edge.
     */
    @Deprecated
    synchronized void quiescent(Ticket ticket) {
        retireAfterPhysicalQuiescence(ticket);
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
        if (!new ScopeKey(oldRoot.domain(), oldRoot.ownerId()).equals(scopeDomain)) {
            throw new IllegalArgumentException("structural barrier outside TaskScope domain");
        }
        AllocationKey key = new AllocationKey(oldRoot.domain(), oldRoot.ownerId(), oldRoot.allocationId());
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
        // No source permission is valid after scope teardown; retire
        // provenance bookkeeping rather than retaining every old allocation.
        knownGenerations.clear();
        scopeDomain = null;
    }

    public synchronized int activeChildren() { return live.size(); }

    private Child child(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.scopeEpoch != scopeEpoch) {
            throw new IllegalArgumentException("foreign Task verifier scope epoch");
        }
        Child result = live.get(ticket);
        if (result == null) throw new IllegalArgumentException("stale Task ticket");
        return result;
    }
}
