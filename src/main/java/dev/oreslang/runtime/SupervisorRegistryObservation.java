package dev.oreslang.runtime;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * First host-only read-only adapter for the runtime UI's supervision hierarchy.
 *
 * This reads REAL registered ActorRuntime supervisors, but is deliberately
 * labelled PARTIAL: it is not a reserved CONTROL request, does not capture
 * actors or membership, and cannot prove globally atomic supervisor health.
 * Never route it directly to a network endpoint or pass grants to guest actors.
 */
public final class SupervisorRegistryObservation implements AutoCloseable {
    public static final int MAX_NODES = 10_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_ACTIVE_READS = 8;
    public static final long MAX_TTL_NANOS = 60_000_000_000L;
    public static final long MAX_READ_NANOS = 5_000_000_000L;
    private static final long REGISTRATION_GENERATION = 1L;

    private final ActorRuntime runtime;
    private final LongSupplier ticks;
    private final Supplier<Instant> wallClock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeReads = new AtomicInteger();

    public record SupervisorNode(String id, String parentId, String status,
                                 long generation, String observedAt) { }

    public record ActorNode(String id, String supervisorId, String spawnedBy,
                            String kind, String status, long generation,
                            String observedAt) { }

    public record Snapshot(int schemaVersion, String snapshotId, String status,
                           String tenantId, String scopeRootId, long scopeGeneration,
                           String startedAt, String finishedAt,
                           List<SupervisorNode> supervisors, List<ActorNode> actors,
                           List<String> missingSupervisorIds, List<String> warnings) {
        public Snapshot {
            supervisors = List.copyOf(supervisors);
            actors = List.copyOf(actors);
            missingSupervisorIds = List.copyOf(missingSupervisorIds);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Unforgeable host-local identity, not a string/ActorRef or network bearer.
     * This grant never implies possession of supervisor CONTROL authority.
     */
    public static final class Grant {
        private final SupervisorRegistryObservation owner;
        private final Object session;
        private final String tenantId;
        private final ActorRuntime.ActorSupervisor scopeRoot;
        private final long issuedAt;
        private final long ttlNanos;
        private final AtomicBoolean revoked = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();
        private Grant(SupervisorRegistryObservation owner, Object session,
                      String tenantId, ActorRuntime.ActorSupervisor scopeRoot,
                      long issuedAt, long ttlNanos) {
            this.owner = owner;
            this.session = session;
            this.tenantId = tenantId;
            this.scopeRoot = scopeRoot;
            this.issuedAt = issuedAt;
            this.ttlNanos = ttlNanos;
        }
    }

    private record Frame(ActorRuntime.SupervisorId id,
                         ActorRuntime.SupervisorId parent, int depth) { }

    public SupervisorRegistryObservation(ActorRuntime runtime) {
        this(runtime, System::nanoTime, Instant::now);
    }

    // A deterministic fake clock allows host-only adversarial deadline tests.
    SupervisorRegistryObservation(ActorRuntime runtime, LongSupplier ticks,
                                  Supplier<Instant> wallClock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        // The bootstrap itself must be host/supervisor-only.
        runtime.supervisorRegistryForObservation();
    }

    public Grant issueGrant(Object authenticatedSession, String tenantId,
                           ActorRuntime.ActorSupervisor scopeRoot, long ttlNanos) {
        Map<ActorRuntime.SupervisorId, ActorRuntime.ActorSupervisor> registry =
                runtime.supervisorRegistryForObservation();
        if (closed.get()) throw new IllegalStateException("observation adapter closed");
        Objects.requireNonNull(authenticatedSession, "authenticatedSession");
        requireId(tenantId, "tenantId");
        Objects.requireNonNull(scopeRoot, "scopeRoot");
        if (registry.get(scopeRoot.id()) != scopeRoot)
            throw new SecurityException("foreign or retired supervisor scope");
        if (ttlNanos <= 0 || ttlNanos > MAX_TTL_NANOS)
            throw new IllegalArgumentException("observation TTL out of range");
        return new Grant(this, authenticatedSession, tenantId, scopeRoot,
                ticks.getAsLong(), ttlNanos);
    }

    public void revoke(Grant grant) {
        requireHost();
        if (grant == null || grant.owner != this)
            throw new SecurityException("foreign observation grant");
        grant.revoked.set(true);
    }

    /**
     * Preflight a CONTROL-dispatched request before reserving VM work.
     * Authorization is checked again inside capture, after dispatch, so
     * revoke/expiry/retirement races cannot bypass this preflight.
     */
    void authorizeControlObservation(Grant grant, Object session, String tenant,
                                     long generation, int maxNodes, int maxDepth,
                                     long budgetNanos) {
        var registry = runtime.supervisorRegistryForObservation();
        requireValid(grant, session, tenant, generation, registry);
        if (maxNodes < 1 || maxNodes > MAX_NODES
                || maxDepth < 0 || maxDepth > MAX_DEPTH
                || budgetNanos <= 0 || budgetNanos > MAX_READ_NANOS) {
            throw new IllegalArgumentException("invalid observation bounds");
        }
    }

    /**
     * Snapshot a subtree from trusted host context; a guest actor, a foreign
     * runtime, or an ActorGroup Mailman cannot make this privileged read.
     *
     * A partial result is returned for a depth/node/deadline budget cut. This
     * v1 bridge always reports partial, even without cuts, because actor
     * lifecycles and CONTROL completion are not yet sampled.
     */
    public Snapshot capture(Grant grant, Object authenticatedSession, String tenantId,
                            long expectedScopeGeneration, int maxNodes, int maxDepth,
                            long budgetNanos) {
        Map<ActorRuntime.SupervisorId, ActorRuntime.ActorSupervisor> registry =
                runtime.supervisorRegistryForObservation();
        requireValid(grant, authenticatedSession, tenantId, expectedScopeGeneration, registry);
        if (maxNodes < 1 || maxNodes > MAX_NODES || maxDepth < 0 || maxDepth > MAX_DEPTH
                || budgetNanos <= 0 || budgetNanos > MAX_READ_NANOS)
            throw new IllegalArgumentException("invalid snapshot bounds");
        if (registry.size() > MAX_NODES)
            throw new IllegalStateException("supervisor registry exceeds observation cap");
        if (!grant.reading.compareAndSet(false, true))
            throw new IllegalStateException("observation grant already has an active read");
        if (activeReads.incrementAndGet() > MAX_ACTIVE_READS) {
            activeReads.decrementAndGet();
            grant.reading.set(false);
            throw new IllegalStateException("observation read capacity exhausted");
        }
        try {
        long start = ticks.getAsLong();
        String startedAt = wallClock.get().toString();
        // Construct structural edges from immutable, runtime-owned registry
        // handles. No actor callbacks, mailbox locks, or guest heap inspection.
        Map<ActorRuntime.SupervisorId, List<ActorRuntime.SupervisorId>> children =
                new HashMap<>();
        for (var supervisor : registry.values()) {
            var parentId = supervisor.parentId().orElse(null);
            if (parentId != null) {
                children.computeIfAbsent(parentId, ignored -> new ArrayList<>())
                        .add(supervisor.id());
            }
        }
        for (var ids : children.values()) {
            ids.sort(Comparator.comparing(id -> id.value().toString()));
        }

        ArrayDeque<Frame> pending = new ArrayDeque<>();
        pending.add(new Frame(grant.scopeRoot.id(), null, 0));
        Set<ActorRuntime.SupervisorId> seen = new HashSet<>();
        List<SupervisorNode> nodes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        warnings.add("SUPERVISOR_REGISTRY_ONLY_NO_CONTROL");
        warnings.add("ACTOR_LIFECYCLE_COVERAGE_UNAVAILABLE");

        while (!pending.isEmpty()) {
            Frame frame = pending.removeFirst();
            if (nodes.size() >= maxNodes || (nodes.size() > 0 &&
                    ticks.getAsLong() - start >= budgetNanos)) {
                missing.add(frame.id().value().toString());
                if (!warnings.contains("BUDGET_EXHAUSTED")) warnings.add("BUDGET_EXHAUSTED");
                // Do not produce an unbounded missing ID inventory.
                while (!pending.isEmpty() && missing.size() < maxNodes) {
                    missing.add(pending.removeFirst().id().value().toString());
                }
                break;
            }
            if (!seen.add(frame.id())) {
                throw new IllegalStateException("duplicate or cyclic supervisor registry edge");
            }
            var supervisor = registry.get(frame.id());
            if (supervisor == null) {
                missing.add(frame.id().value().toString());
                continue;
            }
            String observationTime = wallClock.get().toString();
            nodes.add(new SupervisorNode(supervisor.id().value().toString(),
                    frame.parent() == null ? null : frame.parent().value().toString(),
                    "observed", REGISTRATION_GENERATION, observationTime));
            var direct = children.getOrDefault(frame.id(), List.of());
            for (var child : direct) {
                if (frame.depth() >= maxDepth) {
                    if (missing.size() < maxNodes) missing.add(child.value().toString());
                    if (!warnings.contains("DEPTH_EXHAUSTED")) warnings.add("DEPTH_EXHAUSTED");
                } else {
                    pending.addLast(new Frame(child, frame.id(), frame.depth() + 1));
                }
            }
        }

        // A changed registry invalidates any consistency claim; no private
        // actor state, ActorRef, mailbox payload, or exception text is read.
        var after = runtime.supervisorRegistryForObservation();
        if (!after.keySet().equals(registry.keySet()) || closed.get())
            warnings.add("REGISTRY_CHANGED_DURING_OBSERVATION");
        requireValid(grant, authenticatedSession, tenantId, expectedScopeGeneration, after);
        if (ticks.getAsLong() - start >= budgetNanos)
            warnings.add("READ_DEADLINE_EXCEEDED");

        return new Snapshot(1, UUID.randomUUID().toString(), "partial",
                tenantId, grant.scopeRoot.id().value().toString(),
                REGISTRATION_GENERATION, startedAt, wallClock.get().toString(),
                nodes, List.of(), missing, warnings);
        } finally {
            activeReads.decrementAndGet();
            grant.reading.set(false);
        }
    }

    private void requireValid(Grant grant, Object session, String tenant,
                              long generation,
                              Map<ActorRuntime.SupervisorId,
                                  ActorRuntime.ActorSupervisor> registry) {
        if (closed.get() || grant == null || grant.owner != this ||
                grant.revoked.get() || grant.session != session ||
                !grant.tenantId.equals(tenant) ||
                generation != REGISTRATION_GENERATION ||
                ticks.getAsLong() - grant.issuedAt >= grant.ttlNanos ||
                registry.get(grant.scopeRoot.id()) != grant.scopeRoot)
            throw new SecurityException("observation grant denied");
    }

    private static void requireId(String id, String kind) {
        if (id == null || id.isEmpty() || id.length() > 256 ||
                id.chars().anyMatch(ch -> ch < 32 || ch == 127))
            throw new IllegalArgumentException("invalid " + kind);
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution() || ActorRuntime.inActorGroupMailmanExecution())
            throw new SecurityException("observation authority is host/supervisor-only");
    }

    @Override public void close() {
        requireHost();
        closed.set(true);
    }
}
