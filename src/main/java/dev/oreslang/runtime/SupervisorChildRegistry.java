package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted host bookkeeping for a supervisor's logical children.
 *
 * ChildId is stable across successful incarnation replacements; ActorId is
 * not. This is NOT a guest ActorRef, restart capability, or implementation of
 * lifecycle effects. The owning runtime must prove physical finalization
 * before calling finalized(), and must have successfully created/authorized
 * the replacement before calling replace().
 *
 * Mutations are package-private: guest Oreslang cannot mint a restart token
 * by constructing a ChildId or by reusing a terminated ActorId.
 */
public final class SupervisorChildRegistry {
    public record ChildId(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            UUID logicalId) {
        public ChildId {
            Objects.requireNonNull(supervisorId);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(logicalId);
        }
    }
    public enum Phase { ACTIVE, FINALIZED, RESTARTING, STOPPED }

    /** A single-use host-side claim; the UUID is not a guest capability. */
    public record RestartReservation(ChildId child, ActorRuntime.ActorId failedActor,
                                     long incarnation, UUID nonce) {
        public RestartReservation {
            Objects.requireNonNull(child);
            Objects.requireNonNull(failedActor);
            Objects.requireNonNull(nonce);
            if (incarnation <= 0) throw new IllegalArgumentException("invalid incarnation");
        }
    }
    public record Snapshot(
            ChildId child,
            ActorRuntime.ActorId actor,
            long incarnation,
            Phase phase) {
        public Snapshot {
            Objects.requireNonNull(child);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(phase);
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
        }
    }

    private final ActorRuntime.SupervisorId supervisor;
    private final ActorRuntime.ActorGroupId group;
    private final Map<UUID, Snapshot> children = new HashMap<>();
    private final Map<ActorRuntime.ActorId, UUID> activeActors = new HashMap<>();
    private final Map<UUID, RestartReservation> reservations = new HashMap<>();
    // ActorId must not be recycled (A -> B -> A is another form of ABA).
    // This is a host bookkeeping ledger, bounded by a per-supervisor ceiling.
    private static final int MAX_ACTOR_IDENTITIES = 1_000_000;
    private final Set<ActorRuntime.ActorId> seenActors = new HashSet<>();

    SupervisorChildRegistry(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group) {
        this.supervisor = Objects.requireNonNull(supervisor);
        this.group = Objects.requireNonNull(group);
    }

    /** Caller has already admitted this newly created actor to the group. */
    synchronized Snapshot register(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor);
        requireFreshActor(actor);
        ChildId id = new ChildId(supervisor, group, UUID.randomUUID());
        Snapshot first = new Snapshot(id, actor, 1, Phase.ACTIVE);
        // Stage mutations, then roll back all indexes if an allocation fails.
        seenActors.add(actor);
        try {
            activeActors.put(actor, id.logicalId());
            children.put(id.logicalId(), first);
        } catch (Throwable failure) {
            children.remove(id.logicalId());
            activeActors.remove(actor);
            seenActors.remove(actor);
            throw failure;
        }
        return first;
    }

    public synchronized Snapshot snapshot(ChildId child) {
        return requireCurrent(child);
    }
    /**
     * Trusted owner rolls back an admission or releases a still-active child
     * before it has physically terminated. Never treat this as finalization:
     * no Failure is emitted and no restart is authorized by the old key.
     */
    synchronized void detachActive(ChildId child, ActorRuntime.ActorId actor, long incarnation) {
        Snapshot current = requireExpected(child, actor, incarnation, Phase.ACTIVE);
        activeActors.remove(current.actor());
        children.remove(child.logicalId());
        // Preserve seenActors: a detached actor identity must not be reused
        // as an unrelated incarnation of this supervisor (ABA).
    }


    /**
     * The lifecycle owner must complete the actor's physical finalization
     * before marking its exact incarnation finalized. This check prevents
     * stale finalizers from authorizing replacement of a newer incarnation.
     */
    synchronized Snapshot finalized(
            ChildId child, ActorRuntime.ActorId actor, long incarnation) {
        Snapshot before = requireExpected(child, actor, incarnation, Phase.ACTIVE);
        activeActors.remove(before.actor());
        Snapshot after = new Snapshot(child, actor, incarnation, Phase.FINALIZED);
        children.put(child.logicalId(), after);
        return after;
    }

    /**
     * Claim the finalized generation BEFORE creating a replacement actor.
     * At most one restart attempt may hold this claim at a time. This does
     * not spawn an actor or prove the finalization barrier.
     */
    synchronized RestartReservation beginRestart(
            ChildId child, ActorRuntime.ActorId failedActor, long incarnation) {
        requireExpected(child, failedActor, incarnation, Phase.FINALIZED);
        if (incarnation == Long.MAX_VALUE)
            throw new IllegalStateException("logical child generation exhausted");
        RestartReservation permit = new RestartReservation(
                child, failedActor, incarnation, UUID.randomUUID());
        reservations.put(child.logicalId(), permit);
        children.put(child.logicalId(),
                new Snapshot(child, failedActor, incarnation, Phase.RESTARTING));
        return permit;
    }

    /** Caller must have admitted the actual replacement before commit. */
    synchronized Snapshot replace(
            RestartReservation permit, ActorRuntime.ActorId replacement) {
        checkReservation(permit);
        requireFreshActor(replacement);
        Snapshot after = new Snapshot(
                permit.child(), replacement, permit.incarnation() + 1, Phase.ACTIVE);
        // Commit both actor indexes or neither; a failed commit keeps the
        // reservation so the trusted owner can clean up and abort.
        seenActors.add(replacement);
        try {
            activeActors.put(replacement, permit.child().logicalId());
            children.put(permit.child().logicalId(), after);
        } catch (Throwable failure) {
            activeActors.remove(replacement);
            seenActors.remove(replacement);
            throw failure;
        }
        reservations.remove(permit.child().logicalId());
        return after;
    }

    /** Trusted owner rolls back a failed spawn/admission; STOP may then win. */
    synchronized Snapshot abortRestart(RestartReservation permit) {
        checkReservation(permit);
        Snapshot restored = new Snapshot(
                permit.child(), permit.failedActor(), permit.incarnation(), Phase.FINALIZED);
        children.put(permit.child().logicalId(), restored);
        reservations.remove(permit.child().logicalId());
        return restored;
    }

    private void checkReservation(RestartReservation permit) {
        Objects.requireNonNull(permit);
        requireExpected(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.RESTARTING);
        if (!permit.equals(reservations.get(permit.child().logicalId())))
            throw new IllegalStateException("foreign or stale restart reservation");
    }

    private void requireFreshActor(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor);
        if (seenActors.contains(actor) || activeActors.containsKey(actor))
            throw new IllegalStateException("actor incarnation identity was already used");
        if (seenActors.size() >= MAX_ACTOR_IDENTITIES)
            throw new IllegalStateException("actor identity ledger capacity exhausted");
    }

    /** A permanent STOP applies to the finalized incarnation only. */
    synchronized Snapshot stop(
            ChildId child, ActorRuntime.ActorId failedActor, long incarnation) {
        requireExpected(child, failedActor, incarnation, Phase.FINALIZED);
        Snapshot stopped = new Snapshot(child, failedActor, incarnation, Phase.STOPPED);
        children.put(child.logicalId(), stopped);
        return stopped;
    }

    /** Retire permanently stopped records; old keys never authorize reentry. */
    synchronized void retireStopped(ChildId child) {
        Snapshot state = requireCurrent(child);
        if (state.phase() != Phase.STOPPED)
            throw new IllegalStateException("cannot retire active/restartable child");
        children.remove(child.logicalId());
    }

    public synchronized int trackedChildren() { return children.size(); }
    public synchronized int trackedActorIdentities() { return seenActors.size(); }

    private Snapshot requireCurrent(ChildId id) {
        Objects.requireNonNull(id);
        if (!supervisor.equals(id.supervisorId()) || !group.equals(id.groupId()))
            throw new SecurityException("foreign supervisor child identity");
        Snapshot result = children.get(id.logicalId());
        if (result == null || !result.child().equals(id))
            throw new IllegalStateException("unknown or retired logical child");
        return result;
    }

    private Snapshot requireExpected(
            ChildId id, ActorRuntime.ActorId actor,
            long incarnation, Phase phase) {
        Snapshot current = requireCurrent(id);
        if (!current.actor().equals(Objects.requireNonNull(actor))
                || current.incarnation() != incarnation
                || current.phase() != phase)
            throw new IllegalStateException("stale child incarnation or lifecycle phase");
        return current;
    }
}
