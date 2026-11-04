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
            if (!identity.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
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
            if (boundaries.isEmpty() || boundaries.size() > MAX_BOUNDARIES
                    || boundaries.get(0).kind() != BoundaryKind.ACTOR_TERMINAL) {
                throw new IllegalArgumentException("invalid causal boundary snapshot");
            }
            boundaries = List.copyOf(boundaries);
        }
        public String schema() { return "atto.actor-failure-causality.v1"; }
        public boolean sourceFramesAvailable() { return false; }
        public boolean supervisorControlDelivered() { return false; }
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
