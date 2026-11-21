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
 * capability, or a guest-callable API. It models only SCOPED Task loans.
 * Detached owned moves are admitted separately by ThreadSafetyTraits.isOwnedSend;
 * they must never be laundered through this temporary-loan ledger.
 *
 * <p>The frontend must separately prove lifetime/outlives, allocation-domain
 * admission and capture effects before placing a claim here. Only trusted
 * compiler lowering can declare a field/range layout-proven. Unknown aliasing
 * always conflicts.
 *
 * <p>Admission is atomic across a Task's claims and ledger operations are
 * synchronized (compiler analysis, not runtime host admission). Cancellation
 * never releases loans. Admission returns a one-shot PhysicalCompletion
 * capability tied to exactly one child; trusted lowering must invoke it only
 * from the TaskHandle.getDone()/TaskScope physical-quiescence path.
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

    /** A source-level scoped-loan claim with its precisely resolved referent type. */
    public record TypedClaim(Claim claim, Types.Type type) {
        public TypedClaim {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(type, "type");
            if (containsBorrowCapability(type)) {
                throw new IllegalArgumentException(
                        "nested borrowed referents require an explicit outlives proof");
            }
            // Keep scoped erased-interface borrowing behind its own proof gate.
            // A future dyn I + Send/Sync trait proof must not silently grant
            // concrete-layout, implementation identity, or loan lifetime proof.
            if (Types.containsDynWitness(type)) {
                throw new IllegalArgumentException(
                        "scoped dyn referents require concrete implementation and lifetime proof");
            }
        }
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
                for (Types.Type arg : named.arguments()) pending.push(new Pending(arg, next));
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
                for (Types.Type arg : named.arguments()) pending.push(new Pending(arg, next));
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
     * Validates the structural trait required for one scoped loan before
     * reserving access. READ requires T:Sync; WRITE requires T:Send.
     *
     * <p>This method deliberately does not call isOwnedSend: a scoped loan is
     * not a detached ownership transfer. Lifetime/outlives and physical
     * quiescence are separate obligations carried by the admission capability.
     */
    public synchronized Admission admitScopedTyped(
            List<TypedClaim> requested,
            ThreadSafetyTraits.NominalProof nominalProof) {
        Objects.requireNonNull(nominalProof, "nominalProof");
        Objects.requireNonNull(requested, "claims");
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "scoped Task access requires at least one loan claim");
        }
        if (requested.size() > MAX_CLAIMS_PER_CHILD) {
            throw new IllegalStateException(
                    "TaskScope access capacity exceeded");
        }
        List<TypedClaim> typed = List.copyOf(requested);
        for (TypedClaim claim : typed) {
            if (claim.claim().mode() == Mode.WRITE
                    && containsReadonlyRecordCapability(claim.type())) {
                throw new IllegalArgumentException(
                        "scoped Task WRITE requires mutable referent authority");
            }
            ThreadSafetyTraits.Trait needed =
                    claim.claim().mode() == Mode.READ
                            ? ThreadSafetyTraits.Trait.SYNC
                            : ThreadSafetyTraits.Trait.SEND;
            if (!ThreadSafetyTraits.has(claim.type(), needed, nominalProof)) {
                throw new IllegalArgumentException(
                        "scoped Task access requires proven " + needed);
            }
        }
        Ticket ticket = admitClaims(
                typed.stream().map(TypedClaim::claim).toList());
        return new Admission(
                ticket,
                new PhysicalCompletion(this, ticket));
    }

    /**
     * Opaque compiler admission. The completion capability is the ONLY API
     * that can retire its live loan.
     */
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
     * One-shot capability to be bound by trusted lowering to physical Task
     * quiescence. Cancellation/result settlement alone must never invoke it.
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

    // Bounded compiler analysis: rejecting a huge scope is safer than unbounded
    // memory use or integer overflow. A future MIR implementation can shard.
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CLAIMS_PER_CHILD = 1024;
    private static final int MAX_TRACKED_ALLOCATIONS = 65536;

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
     * Conservatively reserves all scoped child loans, or none. This low-level
     * method is reachable only through admitScopedTyped after trait validation.
     */
    private synchronized Ticket admitClaims(List<Claim> requested) {
        if (sealed) throw new IllegalStateException("TaskScope is sealed");
        Objects.requireNonNull(requested, "claims");
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "scoped Task access requires at least one loan claim");
        }
        if (live.size() >= MAX_CHILDREN
                || requested.size() > MAX_CLAIMS_PER_CHILD) {
            throw new IllegalStateException(
                    "TaskScope access capacity exceeded");
        }
        List<Claim> claims = List.copyOf(requested);
        Map<AllocationKey, Long> incomingGenerations = new HashMap<>();
        ScopeKey requestedDomain = scopeDomain;
        for (Claim claim : claims) {
            Objects.requireNonNull(claim, "claim");
            Root root = claim.place().root();
            ScopeKey claimScope = new ScopeKey(root.domain(), root.ownerId());
            if (requestedDomain == null) requestedDomain = claimScope;
            if (!requestedDomain.equals(claimScope)) {
                throw new IllegalArgumentException("TaskScope cannot mix allocation owner identities");
            }
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
        Ticket ticket = new Ticket(nextId++);
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
     * Called only after child execution, defers, FFI pins and cleanup are
     * physically quiescent. The ledger itself cannot prove runtime quiescence.
     */
    private synchronized void releaseAfterPhysicalQuiescence(Ticket ticket) {
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

    /** Stop admitting children without releasing any existing loan. */
    public synchronized void seal() {
        sealed = true;
    }

    public synchronized void finishScope() {
        if (!sealed) {
            throw new IllegalStateException(
                    "TaskScope must be sealed before final provenance retirement");
        }
        if (!live.isEmpty()) {
            throw new IllegalStateException(
                    "TaskScope cannot finish before physical child quiescence");
        }
        // No source permission is valid after scope teardown; retire
        // provenance bookkeeping rather than retaining every old allocation.
        knownGenerations.clear();
        scopeDomain = null;
    }

    public synchronized int activeChildren() { return live.size(); }

    private Child child(Ticket ticket) {
        Child result = live.get(Objects.requireNonNull(ticket, "ticket"));
        if (result == null) throw new IllegalArgumentException("stale or foreign Task ticket");
        return result;
    }
}
