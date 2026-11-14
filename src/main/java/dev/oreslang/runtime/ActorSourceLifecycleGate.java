package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Runtime-owned lifecycle admission for one physical source actor incarnation.
 *
 * <p>This gate belongs to a single ActorRuntime.ActorCell, never to the shared
 * source image, AST, or guest object. It is deliberately package-private and
 * grants no source-level callable capability. In particular, it is NOT by
 * itself a proof of external `declare actor` lowering or sealed state.</p>
 */
final class ActorSourceLifecycleGate {
    enum Phase {
        NEW, INITIALIZING, READY, STOPPING_GRACEFUL, STOPPING_TERMINAL,
        TEARING_DOWN, FAILED, CLOSED
    }

    private final ActorRuntime.ActorId owner;
    private final String actorType;
    private Phase phase = Phase.NEW;

    ActorSourceLifecycleGate(ActorRuntime.ActorId owner, String actorType) {
        this.owner = Objects.requireNonNull(owner, "source actor owner");
        if (actorType == null || actorType.isBlank()) {
            throw new IllegalArgumentException("source actor type cannot be blank");
        }
        this.actorType = actorType;
    }

    synchronized Phase phase() { return phase; }

    synchronized void beginInit(ActorRuntime.ActorId caller, String type) {
        requireOwner(caller, type);
        require(Phase.NEW, "initialize");
        phase = Phase.INITIALIZING;
    }

    synchronized void commitInit(ActorRuntime.ActorOwnedGuestState created) {
        require(Phase.INITIALIZING, "publish initialized state");
        if (created == null || !owner.equals(created.ownerActorId())) {
            phase = Phase.FAILED;
            throw new SecurityException("source actor initialization returned foreign or null owned state");
        }
        phase = Phase.READY;
    }

    synchronized void failInit() {
        // A late failing callback may not downgrade a STOP or CLOSED fence.
        if (phase == Phase.INITIALIZING) phase = Phase.FAILED;
    }

    /**
     * Seal runtime-only source ingress at the SAME linearization point as the
     * ActorCell STOP CAS, not at eventual physical finalization. This method
     * cannot execute guest code or call back into the actor runtime.
     *
     * In particular, a concurrent initializer may still physically unwind,
     * but its commitInit MUST fail once STOP has won. An already initialized
     * actor may enter one cooperative teardown only for the runtime-approved
     * graceful path. Force/cancel/panic never grants that hook.
     */
    synchronized void sealStop(boolean allowGracefulTeardown) {
        switch (phase) {
            case NEW, INITIALIZING -> phase = Phase.STOPPING_TERMINAL;
            case READY -> phase = allowGracefulTeardown
                    ? Phase.STOPPING_GRACEFUL : Phase.STOPPING_TERMINAL;
            case STOPPING_GRACEFUL -> {
                if (!allowGracefulTeardown) phase = Phase.STOPPING_TERMINAL;
            }
            case STOPPING_TERMINAL, TEARING_DOWN, FAILED, CLOSED -> { /* terminal */ }
        }
    }

    synchronized void requireReceive(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<?> mail) {
        requireOwner(caller, type);
        require(Phase.READY, "deliver source actor mail");
        if (state == null || !owner.equals(state.ownerActorId())
                || mail == null || !owner.equals(mail.recipient())) {
            throw new SecurityException(
                    "source actor mailbox/state owner does not match active incarnation");
        }
    }

    synchronized void beginTeardown(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state) {
        requireOwner(caller, type);
        require(Phase.STOPPING_GRACEFUL, "tear down source actor");
        if (state == null || !owner.equals(state.ownerActorId())) {
            throw new SecurityException(
                    "source actor teardown does not own active incarnation state");
        }
        phase = Phase.TEARING_DOWN;
    }

    synchronized void close() {
        phase = Phase.CLOSED;
    }

    private void requireOwner(ActorRuntime.ActorId caller, String type) {
        if (!owner.equals(caller) || !actorType.equals(type)) {
            throw new SecurityException("source actor lifecycle actor/type identity mismatch");
        }
    }

    private void require(Phase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException(
                    "cannot " + operation + " in source actor lifecycle phase " + phase);
        }
    }
}
