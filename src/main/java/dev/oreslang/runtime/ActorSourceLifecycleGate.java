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

    /** Only the runtime STOP/initializer race may be control cancellation.
     * Other IllegalStateExceptions (including guest state accessors) must
     * retain their original failure identity and not be swallowed by STOP.
     */
    static final class InitializationStopped extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private InitializationStopped() {
            super("source actor initialization commit rejected by STOP");
        }
    }

    private final ActorRuntime.ActorId owner;
    private final String actorType;
    private Phase phase = Phase.NEW;
    // Protected by this gate's monitor. Each accepted guest receive must
    // acquire a lease and release it on every synchronous exit path.
    // Suspended source tasks have a separate physical-quiescence fence in
    // ActorRuntime; this count must never be used as a substitute for it.
    private int activeReceives;
    // A lifecycle callback is itself an actor-owned memory user. STOP/cancel
    // cannot make an already-entered teardown physically safe to reclaim.
    private boolean teardownActive;

    ActorSourceLifecycleGate(ActorRuntime.ActorId owner, String actorType) {
        this.owner = Objects.requireNonNull(owner, "source actor owner");
        if (actorType == null || actorType.isBlank()) {
            throw new IllegalArgumentException("source actor type cannot be blank");
        }
        this.actorType = actorType;
    }

    synchronized Phase phase() { return phase; }

    synchronized int activeReceives() { return activeReceives; }

    synchronized boolean teardownActive() { return teardownActive; }

    /**
     * An unforgeable, single-release admission receipt for one guest receive.
     * A STOP arriving after admission can seal new mail but cannot revoke a
     * still-executing callback's ownership of its receiver frame.
     */
    static final class ReceiveLease implements AutoCloseable {
        private final ActorSourceLifecycleGate gate;
        // One synchronized receipt avoids a second JVM heap allocation for
        // AtomicBoolean on every actor message. A receive lease may be closed
        // on another carrier after a continuation resumes.
        private boolean closed;

        private ReceiveLease(ActorSourceLifecycleGate gate) {
            this.gate = gate;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            gate.leaveReceive();
        }
    }

    synchronized void beginInit(ActorRuntime.ActorId caller, String type) {
        requireOwner(caller, type);
        require(Phase.NEW, "initialize");
        phase = Phase.INITIALIZING;
    }

    synchronized void commitInit(ActorRuntime.ActorOwnedGuestState created) {
        // Use a typed STOP outcome instead of relying on caller observation of
        // phase after an arbitrary IllegalStateException. A malicious or
        // malformed state accessor may throw that very same exception type.
        if (phase == Phase.STOPPING_TERMINAL) {
            throw new InitializationStopped();
        }
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

    synchronized ReceiveLease enterReceive(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<?> mail) {
        // Validation and receipt admission must be ONE atomic operation with
        // respect to STOP. Calling requireReceive() alone is not a lease.
        requireReceive(caller, type, state, mail);
        if (activeReceives == Integer.MAX_VALUE) {
            throw new IllegalStateException("source actor receive lease accounting overflow");
        }
        // Construct the only allocation BEFORE incrementing the count.
        // OutOfMemoryError during receipt allocation must not strand a
        // permanent outstanding receive and block native resource retirement.
        ReceiveLease lease = new ReceiveLease(this);
        activeReceives++;
        return lease;
    }

    private synchronized void leaveReceive() {
        if (activeReceives <= 0) {
            throw new IllegalStateException("source actor receive lease accounting underflow");
        }
        activeReceives--;
        notifyAll();
    }

    synchronized void beginTeardown(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state) {
        requireOwner(caller, type);
        require(Phase.STOPPING_GRACEFUL, "tear down source actor");
        if (activeReceives != 0) {
            throw new IllegalStateException(
                    "source actor tear_down requires all receive invocations to quiesce");
        }
        if (state == null || !owner.equals(state.ownerActorId())) {
            throw new SecurityException(
                    "source actor teardown does not own active incarnation state");
        }
        phase = Phase.TEARING_DOWN;
        teardownActive = true;
    }

    synchronized void finishTeardown(ActorRuntime.ActorId caller, String type) {
        requireOwner(caller, type);
        require(Phase.TEARING_DOWN, "finish source actor teardown");
        if (!teardownActive) {
            throw new IllegalStateException("source actor teardown already completed");
        }
        teardownActive = false;
        notifyAll();
    }

    synchronized void close() {
        if (teardownActive) {
            throw new IllegalStateException(
                    "source actor physical retirement while tear_down is executing");
        }
        if (activeReceives != 0) {
            throw new IllegalStateException(
                    "source actor physical retirement with active receive invocations");
        }
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
