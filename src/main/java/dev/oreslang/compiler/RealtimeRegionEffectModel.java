package dev.oreslang.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed-world symbolic region/effect checker for future typed-IR lowering.
 *
 * IMPORTANT: this analyzes an explicit symbolic plan, NOT arbitrary Oreslang
 * source or the physical JVM heap. A model PASS is not a hard-RT certificate,
 * a memory-provenance proof of an actual actor, or a bound on machine WCET.
 * Trusted compiler lowering must produce this plan and reject omitted effects
 * before this checker can become an admission gate.
 */
public final class RealtimeRegionEffectModel {
    private RealtimeRegionEffectModel() { }

    public enum RegionKind { ACTOR_ARENA, SCOPED, SHARED_IMMUTABLE, MANAGED_HEAP, UNKNOWN }

    public record Region(String id, RegionKind kind, String ownerActor, String trustDomain,
                         long generation, int lifetimeRank, long capacityBytes) {
        public Region {
            requireName(id, "region");
            Objects.requireNonNull(kind, "region kind");
            if (generation < 0 || lifetimeRank < 0 || capacityBytes < 0) deny("invalid region numeric bounds");
            if (kind == RegionKind.ACTOR_ARENA || kind == RegionKind.SCOPED) {
                requireName(ownerActor, "actor owner");
            }
            if (kind == RegionKind.SHARED_IMMUTABLE) requireName(trustDomain, "shared trust domain");
        }
    }

    public sealed interface Op permits Allocate, ImportShared, Borrow, EndBorrow, Move,
            Drop, Store, Send, CopyBytes, Suspend, EndScope, Call, UnknownEffect { }

    /** Allocate a new owned value in an already declared region. */
    public record Allocate(String binding, String region, long bytes, boolean mutable) implements Op { }
    /** Model an existing deeply immutable, previously qualified shared value. */
    public record ImportShared(String binding, String region) implements Op { }
    public record Borrow(String loanId, String binding, boolean mutable) implements Op { }
    public record EndBorrow(String loanId) implements Op { }
    public record Move(String destination, String source) implements Op { }
    public record Drop(String binding) implements Op { }
    public record Store(String binding, String destinationRegion) implements Op { }
    /** Only transmits a qualified shared immutable reference, never an actor-private pointer. */
    public record Send(String binding, String recipientTrustDomain) implements Op { }
    /** Explicitly modeled bounded byte copy, not proof of a concrete serializer. */
    public record CopyBytes(long bytes) implements Op { }
    public record Suspend() implements Op { }
    public record EndScope(String region) implements Op { }
    public record Call(String function) implements Op { }
    public record UnknownEffect(String effect) implements Op { }

    public record Function(String name, List<Op> instructions) {
        public Function {
            requireName(name, "function");
            instructions = List.copyOf(instructions);
        }
    }

    public record Budget(long maxArenaBytes, long maxCopiedBytes, int maxOperations, int maxCallDepth) {
        public Budget {
            if (maxArenaBytes < 0 || maxCopiedBytes < 0 || maxOperations < 1 || maxCallDepth < 1) {
                deny("invalid resource budgets");
            }
        }
    }

    public record Plan(String actorId, String entry, List<Region> regions,
                       Set<String> authorizedDomains, List<Function> functions, Budget budget) {
        public Plan {
            requireName(actorId, "actor");
            requireName(entry, "entry");
            regions = List.copyOf(regions);
            authorizedDomains = Set.copyOf(authorizedDomains);
            functions = List.copyOf(functions);
            Objects.requireNonNull(budget, "budget");
        }
    }

    public record Report(long arenaBytesAllocated, long copiedBytes, long modeledOperations,
                         int maxCallDepth, String assurance) { }

    private record Binding(String region, long generation, boolean mutable) { }
    private record Loan(String binding, boolean mutable) { }

    private static final class Check {
        private final Plan plan;
        private final Map<String, Region> regions = new LinkedHashMap<>();
        private final Map<String, Function> functions = new LinkedHashMap<>();
        private final Map<String, Binding> bindings = new HashMap<>();
        private final Map<String, Loan> loans = new HashMap<>();
        private final Map<String, Long> used = new HashMap<>();
        // Destination region -> source regions whose addresses remain stored.
        // A Drop/Move of the source binding does NOT erase a stored pointer.
        private final Map<String, Set<String>> retainedRegions = new HashMap<>();
        // Track *value bindings* too: Drop may run a destructor and invalidate
        // an object even while its owning arena remains allocated. A transfer
        // of a retained binding requires a dedicated proven relocation rule.
        private final Map<String, Set<String>> retainedBindings = new HashMap<>();
        private final Set<String> ended = new HashSet<>();
        private final ArrayDeque<String> active = new ArrayDeque<>();
        private long allocated;
        private long copied;
        private long operations;
        private int depth;

        Check(Plan plan) {
            this.plan = plan;
            for (Region r : plan.regions()) {
                if (regions.putIfAbsent(r.id(), r) != null) deny("duplicate region " + r.id());
            }
            for (Function f : plan.functions()) {
                if (functions.putIfAbsent(f.name(), f) != null) deny("duplicate function " + f.name());
            }
            if (functions.isEmpty()) deny("empty call graph");
        }

        Report run() {
            visit(plan.entry());
            if (!loans.isEmpty()) deny("borrow survived analyzed entry");
            // A caller cannot claim bounded scope cleanup while leaving the
            // scope live, even when its last local binding was dropped.
            for (Region r : regions.values()) {
                if (r.kind() == RegionKind.SCOPED && !ended.contains(r.id())) {
                    deny("scoped region was not explicitly closed: " + r.id());
                }
            }
            return new Report(allocated, copied, operations, depth,
                    "SYMBOLIC_REGION_MODEL_ONLY;SOURCE_LOWERING_AND_PHYSICAL_VM_UNVERIFIED");
        }

        private void visit(String name) {
            Function f = functions.get(name);
            if (f == null) deny("unknown call " + name);
            if (active.contains(name)) deny("recursive call graph");
            if (active.size() >= plan.budget().maxCallDepth()) deny("call depth exceeds budget");
            active.addLast(name);
            depth = Math.max(depth, active.size());
            try {
                for (Op op : f.instructions()) {
                    operations = sum(operations, 1);
                    if (operations > plan.budget().maxOperations()) deny("modeled operation budget exceeded");
                    apply(op);
                }
            } finally {
                active.removeLast();
            }
        }

        private Region region(String id) {
            Region r = regions.get(id);
            if (r == null || ended.contains(id)) deny("unknown or expired region " + id);
            return r;
        }

        private Binding binding(String id) {
            Binding b = bindings.get(id);
            if (b == null) deny("unknown, stale, or moved value " + id);
            Region region = region(b.region());
            if (region.generation() != b.generation()) deny("stale generation " + id);
            return b;
        }

        private void put(String id, Binding b) {
            requireName(id, "binding");
            if (bindings.putIfAbsent(id, b) != null || loans.containsKey(id)) {
                deny("duplicate binding " + id);
            }
        }

        private boolean hasLoan(String binding) {
            return loans.values().stream().anyMatch(l -> l.binding().equals(binding));
        }

        private boolean isRetained(String binding) {
            for (var entry : retainedBindings.entrySet()) {
                if (!ended.contains(entry.getKey()) && entry.getValue().contains(binding)) {
                    return true;
                }
            }
            return false;
        }

        private void apply(Op op) {
            Objects.requireNonNull(op, "unverified null operation");
            if (op instanceof Allocate a) {
                if (a.bytes() <= 0) deny("allocation size must be positive");
                Region r = region(a.region());
                if (r.kind() != RegionKind.ACTOR_ARENA && r.kind() != RegionKind.SCOPED) {
                    deny("critical allocation into unmanaged or immutable region");
                }
                if (!plan.actorId().equals(r.ownerActor())) deny("cross-actor private allocation");
                long next = sum(used.getOrDefault(r.id(), 0L), a.bytes());
                if (next > r.capacityBytes()) deny("region capacity exceeded");
                used.put(r.id(), next);
                allocated = sum(allocated, a.bytes());
                if (allocated > plan.budget().maxArenaBytes()) deny("allocation budget exceeded");
                put(a.binding(), new Binding(r.id(), r.generation(), a.mutable()));
            } else if (op instanceof ImportShared s) {
                Region r = region(s.region());
                if (r.kind() != RegionKind.SHARED_IMMUTABLE
                        || !plan.authorizedDomains().contains(r.trustDomain())) {
                    deny("unqualified immutable shared import");
                }
                put(s.binding(), new Binding(r.id(), r.generation(), false));
            } else if (op instanceof Borrow b) {
                Binding source = binding(b.binding());
                requireName(b.loanId(), "loan");
                if (loans.containsKey(b.loanId()) || bindings.containsKey(b.loanId())) deny("duplicate loan");
                if (b.mutable() && !source.mutable()) deny("mutable borrow of immutable value");
                for (Loan live : loans.values()) {
                    if (live.binding().equals(b.binding()) && (live.mutable() || b.mutable())) {
                        deny("exclusive borrow overlap");
                    }
                }
                loans.put(b.loanId(), new Loan(b.binding(), b.mutable()));
            } else if (op instanceof EndBorrow end) {
                if (loans.remove(end.loanId()) == null) deny("unknown loan release");
            } else if (op instanceof Move m) {
                Binding source = binding(m.source());
                if (hasLoan(m.source())) deny("move with live borrow");
                if (isRetained(m.source())) deny("move while another region retains its address");
                put(m.destination(), source);
                bindings.remove(m.source());
            } else if (op instanceof Drop d) {
                binding(d.binding());
                if (hasLoan(d.binding())) deny("drop with live borrow");
                if (isRetained(d.binding())) deny("drop could destroy a retained reference");
                bindings.remove(d.binding());
            } else if (op instanceof Store store) {
                Binding value = binding(store.binding());
                Region source = region(value.region()), destination = region(store.destinationRegion());
                if (source.lifetimeRank() < destination.lifetimeRank()) {
                    deny("shorter-lived value escaped into longer-lived region");
                }
                if (destination.kind() == RegionKind.MANAGED_HEAP || destination.kind() == RegionKind.UNKNOWN) {
                    deny("unknown or managed-heap destination");
                }
                if (destination.kind() == RegionKind.SHARED_IMMUTABLE) {
                    if (source.kind() != RegionKind.SHARED_IMMUTABLE
                            || !source.trustDomain().equals(destination.trustDomain())
                            || value.mutable()) deny("actor-private or mutable pointer escaped into shared region");
                } else if (!plan.actorId().equals(destination.ownerActor())
                        || !plan.actorId().equals(source.ownerActor())) {
                    deny("cross-actor private region escape");
                }
                if (hasLoan(store.binding())) deny("storing borrowed owner requires lifetime proof");
                // The destination retains a pointer independently of the
                // source binding. Its lifetime must be checked at scope close.
                retainedRegions.computeIfAbsent(destination.id(), ignored -> new HashSet<>())
                        .add(source.id());
                retainedBindings.computeIfAbsent(destination.id(), ignored -> new HashSet<>())
                        .add(store.binding());
            } else if (op instanceof Send send) {
                Binding value = binding(send.binding());
                Region source = region(value.region());
                if (source.kind() != RegionKind.SHARED_IMMUTABLE || value.mutable()
                        || !Objects.equals(source.trustDomain(), send.recipientTrustDomain())
                        || !plan.authorizedDomains().contains(send.recipientTrustDomain())) {
                    deny("mailbox cannot export raw private or unauthorized reference");
                }
            } else if (op instanceof CopyBytes c) {
                if (c.bytes() < 0) deny("negative copy size");
                copied = sum(copied, c.bytes());
                if (copied > plan.budget().maxCopiedBytes()) deny("copy budget exceeded");
            } else if (op instanceof Suspend) {
                if (!loans.isEmpty()) deny("borrow crossing suspension without lifetime proof");
            } else if (op instanceof EndScope end) {
                Region r = region(end.region());
                if (r.kind() != RegionKind.SCOPED) deny("only scoped region may be closed explicitly");
                if (bindings.values().stream().anyMatch(v -> v.region().equals(r.id()))
                        || loans.values().stream().anyMatch(v -> {
                            Binding ref = bindings.get(v.binding());
                            return ref != null && ref.region().equals(r.id());
                        })) {
                    deny("scope closed with live references or borrows");
                }
                // Ending a scope invalidates every address allocated inside
                // it. Other still-live regions must not retain such pointers.
                for (var entry : retainedRegions.entrySet()) {
                    if (!entry.getKey().equals(r.id()) && !ended.contains(entry.getKey())
                            && entry.getValue().contains(r.id())) {
                        deny("scope closed while another region retains its address: "
                                + entry.getKey() + " -> " + r.id());
                    }
                }
                // References stored *inside* this scope die with that scope.
                retainedRegions.remove(r.id());
                retainedBindings.remove(r.id());
                ended.add(r.id());
                used.put(r.id(), 0L);
            } else if (op instanceof Call call) {
                visit(call.function());
            } else {
                deny("unknown/unqualified effect");
            }
        }
    }

    public static Report check(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return new Check(plan).run();
    }

    private static long sum(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("RT-REGION: resource overflow"); }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isBlank()) deny("missing " + what);
    }

    private static void deny(String reason) {
        throw new IllegalArgumentException("RT-REGION: " + reason);
    }
}
