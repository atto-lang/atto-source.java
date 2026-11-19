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
    // A running initializer owns actor allocation authority across STOP.
    // Phase STOPPING_TERMINAL alone does not imply that it has unwound.
    private boolean initializationActive;
    private boolean stopSealed;
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

    synchronized boolean initializationActive() { return initializationActive; }

    /**
     * The runtime checks this BEFORE publishing finalized=true. close() still
     * asserts the same contract as a last line of defense against misordered
     * state release. No user callback runs while this gate monitor is held.
     */
    synchronized boolean readyForRetirement() {
        return stopSealed && !initializationActive && activeReceives == 0
                && !teardownActive && phase != Phase.STOPPING_GRACEFUL;
    }

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
        // STOP can win after the outer runtime's initial !stopped check but
        // before this reservation. Return the same typed STOP result as a
        // late commit; neither outcome is a guest exception.
        if (phase == Phase.STOPPING_TERMINAL) throw new InitializationStopped();
        require(Phase.NEW, "initialize");
        initializationActive = true;
        phase = Phase.INITIALIZING;
    }

    void commitInit(ActorRuntime.ActorOwnedGuestState created) {
        // ActorOwnedGuestState is supplied by a host/guest executor. Its owner
        // accessor may throw, park, or reenter runtime APIs. Never call it
        // while holding the gate's admission/STOP monitor.
        synchronized (this) {
            if (phase == Phase.STOPPING_TERMINAL) throw new InitializationStopped();
            require(Phase.INITIALIZING, "publish initialized state");
        }
        ActorRuntime.ActorId observedOwner =
                created == null ? null : created.ownerActorId();
        synchronized (this) {
            // STOP may win while the untrusted accessor is executing.
            if (phase == Phase.STOPPING_TERMINAL) throw new InitializationStopped();
            require(Phase.INITIALIZING, "publish initialized state");
            if (!owner.equals(observedOwner)) {
                initializationActive = false;
                phase = Phase.FAILED;
                throw new SecurityException(
                        "source actor initialization returned foreign or null owned state");
            }
            initializationActive = false;
            phase = Phase.READY;
        }
    }

    synchronized void failInit() {
        // This is called when the physical initializer has actually unwound,
        // even if STOP has already advanced the lifecycle phase.
        if (phase == Phase.INITIALIZING) phase = Phase.FAILED;
        initializationActive = false;
        notifyAll();
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
        stopSealed = true;
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

    void requireReceive(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<?> mail) {
        // Only immutable ownership snapshots may enter a gate monitor.
        ActorRuntime.ActorId stateOwner =
                state == null ? null : state.ownerActorId();
        ActorRuntime.ActorId recipient =
                mail == null ? null : mail.recipient();
        synchronized (this) {
            requireReceiveOwners(caller, type, stateOwner, recipient);
        }
    }

    ReceiveLease enterReceive(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<?> mail) {
        ActorRuntime.ActorId stateOwner =
                state == null ? null : state.ownerActorId();
        ActorRuntime.ActorId recipient =
                mail == null ? null : mail.recipient();
        return enterReceiveOwners(caller, type, stateOwner, recipient);
    }

    /**
     * Trusted actor-cell dispatch must obtain state/mailbox owner snapshots
     * OUTSIDE lifecycleLock, then linearize this operation against STOP while
     * holding lifecycleLock. This method never calls guest/host user code.
     */
    synchronized ReceiveLease enterReceiveOwners(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorId stateOwner, ActorRuntime.ActorId recipient) {
        requireReceiveOwners(caller, type, stateOwner, recipient);
        if (activeReceives == Integer.MAX_VALUE) {
            throw new IllegalStateException("source actor receive lease accounting overflow");
        }
        // Construct the only allocation BEFORE incrementing the count.
        // OutOfMemoryError cannot strand a permanent outstanding receipt.
        ReceiveLease lease = new ReceiveLease(this);
        activeReceives++;
        return lease;
    }

    private void requireReceiveOwners(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorId stateOwner, ActorRuntime.ActorId recipient) {
        requireOwner(caller, type);
        require(Phase.READY, "deliver source actor mail");
        if (!owner.equals(stateOwner) || !owner.equals(recipient)) {
            throw new SecurityException(
                    "source actor mailbox/state owner does not match active incarnation");
        }
    }

    private synchronized void leaveReceive() {
        if (activeReceives <= 0) {
            throw new IllegalStateException("source actor receive lease accounting underflow");
        }
        activeReceives--;
        notifyAll();
    }

    void beginTeardown(
            ActorRuntime.ActorId caller, String type,
            ActorRuntime.ActorOwnedGuestState state) {
        ActorRuntime.ActorId stateOwner =
                state == null ? null : state.ownerActorId();
        synchronized (this) {
            requireOwner(caller, type);
            require(Phase.STOPPING_GRACEFUL, "tear down source actor");
            if (activeReceives != 0) {
                throw new IllegalStateException(
                        "source actor tear_down requires all receive invocations to quiesce");
            }
            if (!owner.equals(stateOwner)) {
                throw new SecurityException(
                        "source actor teardown does not own active incarnation state");
            }
            phase = Phase.TEARING_DOWN;
            teardownActive = true;
        }
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
        // A trusted finalizer must not release a live actor merely because it
        // sees zero receiver leases. STOP, initialization completion and the
        // graceful lifecycle callback are separate mandatory barriers.
        if (!stopSealed || initializationActive) {
            throw new IllegalStateException(
                    "source actor physical retirement before STOP and init quiescence");
        }
        if (phase == Phase.STOPPING_GRACEFUL) {
            throw new IllegalStateException(
                    "source actor graceful retirement before tear_down completion");
        }
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
