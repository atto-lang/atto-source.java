package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in trusted-host bridge from real ActorRuntime terminal events to the
 * bounded supervisor CONTROL escrow. This is not a guest supervisor, restart
 * policy, or second production terminal ledger.
 *
 * All actor admission/terminal callbacks come from the owning ActorRuntime.
 * Policy decisions, physical resource receipts, and guest source lowering
 * are deliberately not implemented by this class.
 */
final class SupervisorFailureEscrowRuntimeBridgeV1 {
    private static final long GUARDIAN = 1L;
    private final ActorRuntime runtime;
    private final SupervisorFailureEscrowV1 ledger = new SupervisorFailureEscrowV1();
    private final Map<ActorRuntime.ActorId, Long> ids = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(2L);
    private final Object decisionLock = new Object();
    private volatile SupervisorEscrowSubtreeRecoveryV1 recovery;
    private final Set<SupervisorFailureEscrowV1.Delivery> inFlightDecisions =
            Collections.newSetFromMap(new IdentityHashMap<>());

    SupervisorFailureEscrowRuntimeBridgeV1(ActorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        runtime.requireSupervisorShutdownAuthority("construct host supervisor CONTROL bridge");
        ledger.registerRoot(GUARDIAN, 1L);
    }

    /**
     * Bind a fresh, unscheduled runtime actor to an already-bound parent;
     * a null parent selects the trusted in-process guardian. The host MUST
     * call this before ready/send, never after an actor starts executing.
     * Physical ActorRef identity/generation are checked by ActorRuntime.
     */
    long attach(ActorRuntime.ActorRef<?> actor, ActorRuntime.ActorRef<?> supervisor) {
        runtime.requireSupervisorShutdownAuthority("attach supervisor CONTROL child");
        Objects.requireNonNull(actor, "actor");
        long parentId = GUARDIAN;
        if (supervisor != null) {
            Long boundParent = ids.get(supervisor.id());
            if (boundParent == null) {
                throw new IllegalStateException("supervisor must be attached first");
            }
            parentId = boundParent;
        }
        long nodeId = nextId.getAndIncrement();
        if (nodeId < 2) {
            throw new IllegalStateException("supervisor CONTROL identities exhausted");
        }
        runtime.attachSupervisorEscrowActor(actor, this, nodeId, parentId);
        Long prior = ids.putIfAbsent(actor.id(), nodeId);
        if (prior != null) {
            throw new IllegalStateException("actor CONTROL binding already exists");
        }
        return nodeId;
    }

    /** Install one trusted, opt-in CONTROL coordinator. No actor guest may call
     * this method and no second coordinator may compete for this ledger. */
    synchronized void installRecovery(SupervisorEscrowSubtreeRecoveryV1 coordinator) {
        runtime.requireSupervisorShutdownAuthority("install supervisor recovery CONTROL");
        Objects.requireNonNull(coordinator, "coordinator");
        if (recovery != null) throw new IllegalStateException("CONTROL coordinator already installed");
        recovery = coordinator;
    }

    /** An internal mapping used only for generation-fenced replacement checks. */
    long nodeId(ActorRuntime.ActorRef<?> ref) {
        runtime.requireSupervisorShutdownAuthority("inspect trusted supervisor node identity");
        Objects.requireNonNull(ref, "ref");
        Long id = ids.get(ref.id());
        if (id == null) throw new IllegalStateException("unknown supervisor parent");
        var node = ledger.snapshot().get(id);
        if (node == null || node.generation() != ref.generation()
                || !node.alive() || node.fenced()) {
            throw new IllegalStateException("inactive or stale supervisor parent");
        }
        return id;
    }

    /** Called only by ActorRuntime while publishing a new trusted binding. */
    void registerFromRuntime(ActorRuntime.ActorRef<?> actor, long id, long parentId) {
        ledger.registerChild(id, parentId, actor.generation());
    }

    /** Called by ActorRuntime at the first non-cancellation terminal fault. */
    void failedFromRuntime(long id) {
        ledger.fail(id);
        // The ledger is fenced before the notification; the coordinator only
        // queues CONTROL work and may not run guest or child callbacks inline.
        SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
        if (coordinator != null) coordinator.failedFromRuntime(id);
    }

    /** Called by ActorRuntime after real actor finalization. */
    void finalizedFromRuntime(long id, boolean failed) {
        if (failed) {
            // An on_stop/final-cleanup fault may be the first failure.
            failedFromRuntime(id);
        } else {
            ledger.completeNormally(id);
            // Even a clean supervisor exit must retire its managed subtree.
            SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
            if (coordinator != null) coordinator.failedFromRuntime(id);
        }
    }

    /** Preliminary check only; not a commit or authorization to enqueue. */
    boolean mayAdmitFromRuntime(long id, long generation) {
        return ledger.mayAdmit(id, generation);
    }

    /** Logical DATA admission linearizes against the ancestor-failure fence
     * inside one escrow monitor. Must occur at the final mailbox commit path. */
    SupervisorFailureEscrowV1.DataAdmission beginDataAdmissionFromRuntime(
            long id, long generation) {
        return ledger.beginDataAdmission(id, generation);
    }

    /** This is the only way to release a successful or rolled-back permit. */
    void finishDataAdmissionFromRuntime(
            SupervisorFailureEscrowV1.DataAdmission permit, boolean committed) {
        ledger.finishDataAdmission(permit, committed);
    }

    int dataInFlightInSubtree(long id) {
        return ledger.dataInFlightInSubtree(id);
    }

    /** Detached diagnostic count, not proof of physically processed DATA. */
    SupervisorFailureEscrowV1.DataAdmissionStatus dataAdmissionStatus(long id) {
        runtime.requireSupervisorShutdownAuthority("inspect DATA admission trace counters");
        return ledger.dataAdmissionStatus(id);
    }

    SupervisorFailureEscrowV1.Delivery deliver(long id) {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor failure CONTROL");
        return ledger.deliver(id);
    }

    /**
     * Host-only single-flight decision/ACK boundary. The sink is executed
     * OUTSIDE escrow and runtime locks, and a failed sink never ACKs. The
     * delivery is generation-revalidated after the sink returns.
     *
     * This does NOT make an external effect exactly once: a controller may
     * crash after an effect but before ACK. The downstream decision sink
     * MUST be idempotent or journaled by the production SupervisorProcess.
     */
    boolean commitAndAcknowledge(
            SupervisorFailureEscrowV1.Delivery receipt, Runnable commitAction) {
        runtime.requireSupervisorShutdownAuthority("commit supervisor CONTROL decision");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(commitAction, "commitAction");
        synchronized (decisionLock) {
            if (!ledger.currentDelivery(receipt) || !inFlightDecisions.add(receipt)) {
                return false;
            }
        }
        try {
            commitAction.run();
            return ledger.acknowledge(receipt);
        } finally {
            synchronized (decisionLock) {
                inFlightDecisions.remove(receipt);
            }
        }
    }

    int pendingCount() {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor CONTROL pressure");
        return ledger.pendingCount();
    }

    Map<Long, SupervisorFailureEscrowV1.NodeStatus> snapshot() {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor CONTROL snapshot");
        return ledger.snapshot();
    }
}
