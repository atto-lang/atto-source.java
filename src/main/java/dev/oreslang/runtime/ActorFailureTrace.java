package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Detached, bounded, host-owned causal provenance for a failed actor.
 *
 * This is data, not the Throwable, a guest stack, a CONTROL receipt, an actor
 * capability, a memory lease or proof that a supervisor handler ran. It can
 * outlive an actor's heap/arena without retaining anything allocated there.
 */
public final class ActorFailureTrace {
    public static final int MAX_PARENT_ACTORS = 8;
    public static final int MAX_SUPERVISORS = 8;
    public static final int MAX_BOUNDARIES = 1 + MAX_PARENT_ACTORS + MAX_SUPERVISORS;

    public enum FailureKind { UNCLASSIFIED, CANCELLED }
    public enum BoundaryKind { ACTOR_TERMINAL, PARENT_ACTOR, SUPERVISOR_ASSOCIATION }

    /** All identifiers are detached host-issued UUID strings, never references. */
    public record Boundary(BoundaryKind kind, String identity, long actorGeneration) {
        public Boundary {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            if (identity.length() != 36
                    || !identity.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException("boundary identity must be a trusted UUID");
            }
            if (actorGeneration < 0) throw new IllegalArgumentException("negative generation");
            if (kind == BoundaryKind.ACTOR_TERMINAL && actorGeneration == 0) {
                throw new IllegalArgumentException("actor terminal generation must be positive");
            }
            if (kind != BoundaryKind.ACTOR_TERMINAL && actorGeneration != 0) {
                throw new IllegalArgumentException("no invented ancestor generation");
            }
        }
    }

    /**
     * Source frames are explicitly unavailable: never synthesize a source
     * call stack from a Java carrier or the parent's supervisory association.
     * CONTROL delivery is separately unproven; associations are not receipts.
     */
    public record Snapshot(FailureKind failureKind, List<Boundary> boundaries,
                           boolean truncated, boolean supervisorAssociationKnown) {
        public Snapshot {
            Objects.requireNonNull(failureKind, "failureKind");
            Objects.requireNonNull(boundaries, "boundaries");
            // Do not call size/get/iterator on a guest-defined List while
            // constructing privileged supervisor diagnostic evidence.
            Class<?> sourceClass = boundaries.getClass();
            if (sourceClass != ArrayList.class
                    && !sourceClass.getName().startsWith("java.util.ImmutableCollections$")) {
                throw new IllegalArgumentException("diagnostic boundaries require a host-owned list");
            }
            // Bound allocation BEFORE copying. List.copyOf(original) could
            // allocate an arbitrarily large guest-controlled collection before
            // the post-copy size check. Because the exact class is host-owned,
            // size/get are trusted, but every read is explicitly capped.
            // A concurrent mutation may reject the snapshot, never increase
            // the amount copied or publish a live mutable list.
            int count = boundaries.size();
            if (count < 1 || count > MAX_BOUNDARIES) {
                throw new IllegalArgumentException("unbounded causal boundary snapshot");
            }
            ArrayList<Boundary> bounded = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                bounded.add(Objects.requireNonNull(boundaries.get(i), "boundary"));
            }
            List<Boundary> detached = List.copyOf(bounded);
            if (detached.isEmpty() || detached.size() > MAX_BOUNDARIES
                    || detached.get(0).kind() != BoundaryKind.ACTOR_TERMINAL) {
                throw new IllegalArgumentException("invalid causal boundary snapshot");
            }
            int parentCount = 0;
            int supervisorCount = 0;
            boolean seenSupervisor = false;
            for (int i = 0; i < detached.size(); i++) {
                Boundary boundary = detached.get(i);
                if (i != 0) {
                    if (boundary.kind() == BoundaryKind.ACTOR_TERMINAL) {
                        throw new IllegalArgumentException("duplicate terminal actor boundary");
                    }
                    if (boundary.kind() == BoundaryKind.PARENT_ACTOR) {
                        if (seenSupervisor || ++parentCount > MAX_PARENT_ACTORS) {
                            throw new IllegalArgumentException("invalid parent actor lineage");
                        }
                    } else if (boundary.kind() == BoundaryKind.SUPERVISOR_ASSOCIATION) {
                        seenSupervisor = true;
                        if (++supervisorCount > MAX_SUPERVISORS) {
                            throw new IllegalArgumentException("unbounded supervisor lineage");
                        }
                    } else {
                        throw new IllegalArgumentException("unknown causal boundary kind");
                    }
                }
                // With at most 17 entries a fixed bounded scan is cheaper and
                // avoids allocating a guest-facing hash table or traversing
                // any untrusted adapter while validating the captured value.
                for (int j = 0; j < i; j++) {
                    if (boundary.identity().equals(detached.get(j).identity())) {
                        throw new IllegalArgumentException("repeated causal identity");
                    }
                }
            }
            if (supervisorAssociationKnown != (supervisorCount > 0)) {
                throw new IllegalArgumentException("inconsistent supervisor association");
            }
            boundaries = detached;
        }
        public String schema() { return "atto.actor-failure-causality.v1"; }
        public boolean sourceFramesAvailable() { return false; }
        public boolean supervisorControlDelivered() { return false; }
    }

    /**
     * Detached failure observed by actor lifecycle Futures. A raw Throwable
     * cannot be the exceptional result of ready(), readyRef(), or done():
     * its cause/suppressed frames may retain another actor's heap, arena loans
     * or mutable guest state. This value is diagnostic data only, never a
     * supervisor CONTROL receipt or permission to inspect actor memory.
     */
    public static final class ObservationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String actorIdentity;
        private final long actorGeneration;
        private final FailureKind failureKind;

        private ObservationFailure(UUID actor, long generation, FailureKind kind) {
            super("actor lifecycle failed (detached diagnostic)", null, false, false);
            Objects.requireNonNull(actor, "actor");
            if (generation <= 0) throw new IllegalArgumentException("negative actor generation");
            String identity = actor.toString();
            actorIdentity = identity;
            actorGeneration = generation;
            failureKind = Objects.requireNonNull(kind, "kind");
        }

        public String schema() { return "atto.actor-failure-observation.v1"; }
        public String actorIdentity() { return actorIdentity; }
        public long actorGeneration() { return actorGeneration; }
        public FailureKind failureKind() { return failureKind; }
        // Supervisor ancestry is deliberately *not* exported by public observer Futures.
        /** Neither source frames nor supervisor delivery may be inferred. */
        public boolean supervisorControlDelivered() { return false; }
    }

    /** Only runtime-owned metadata can manufacture a detached observer failure. */
    static ObservationFailure observation(UUID actor, long generation, FailureKind kind) {
        return new ObservationFailure(actor, generation, kind);
    }

    private ActorFailureTrace() { }

    /**
     * Only trusted runtime metadata is admissible. ActorRuntime must cap each
     * parent/supervisor walk before calling this factory. No guest Throwable,
     * Throwable.getMessage/getStackTrace, arbitrary List, pointer or arena
     * handle crosses into the snapshot.
     */
    static Snapshot capture(FailureKind kind, UUID actor, long generation,
                            List<UUID> parents, List<UUID> supervisors,
                            boolean truncated, boolean associationKnown) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(parents, "parents");
        Objects.requireNonNull(supervisors, "supervisors");
        if (generation <= 0 || parents.size() > MAX_PARENT_ACTORS
                || supervisors.size() > MAX_SUPERVISORS) {
            throw new IllegalArgumentException("unbounded causal metadata");
        }
        if (!associationKnown && !supervisors.isEmpty()) {
            throw new IllegalArgumentException("unknown supervisor route has identities");
        }
        List<Boundary> detached = new ArrayList<>(1 + parents.size() + supervisors.size());
        detached.add(new Boundary(BoundaryKind.ACTOR_TERMINAL, actor.toString(), generation));
        for (UUID parent : parents) {
            detached.add(new Boundary(BoundaryKind.PARENT_ACTOR,
                    Objects.requireNonNull(parent, "parent").toString(), 0));
        }
        for (UUID supervisor : supervisors) {
            detached.add(new Boundary(BoundaryKind.SUPERVISOR_ASSOCIATION,
                    Objects.requireNonNull(supervisor, "supervisor").toString(), 0));
        }
        return new Snapshot(kind, detached, truncated, associationKnown);
    }
}
