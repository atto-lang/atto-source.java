package dev.oreslang.runtime;

import java.util.HashMap;
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
    public enum Phase { ACTIVE, FINALIZED, STOPPED }
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

    SupervisorChildRegistry(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group) {
        this.supervisor = Objects.requireNonNull(supervisor);
        this.group = Objects.requireNonNull(group);
    }

    /** Caller has already admitted this newly created actor to the group. */
    synchronized Snapshot register(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor);
        if (activeActors.containsKey(actor))
            throw new IllegalStateException("actor already has a logical child");
        ChildId id = new ChildId(supervisor, group, UUID.randomUUID());
        Snapshot first = new Snapshot(id, actor, 1, Phase.ACTIVE);
        children.put(id.logicalId(), first);
        activeActors.put(actor, id.logicalId());
        return first;
    }

    public synchronized Snapshot snapshot(ChildId child) {
        return requireCurrent(child);
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
     * Commit identity only after trusted host spawn/admission succeeds. This
     * function does not spawn, restart, or authorize actors itself.
     */
    synchronized Snapshot replace(
            ChildId child, ActorRuntime.ActorId failedActor,
            long failedIncarnation, ActorRuntime.ActorId replacement) {
        Objects.requireNonNull(replacement);
        Snapshot before = requireExpected(
                child, failedActor, failedIncarnation, Phase.FINALIZED);
        if (replacement.equals(failedActor) || activeActors.containsKey(replacement))
            throw new IllegalStateException("replacement actor identity is not fresh");
        if (failedIncarnation == Long.MAX_VALUE)
            throw new IllegalStateException("logical child generation exhausted");
        Snapshot after = new Snapshot(
                child, replacement, failedIncarnation + 1, Phase.ACTIVE);
        children.put(child.logicalId(), after);
        activeActors.put(replacement, child.logicalId());
        return after;
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
