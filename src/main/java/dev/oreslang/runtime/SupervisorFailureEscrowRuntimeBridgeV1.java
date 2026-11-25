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
        // The runtime owns the lock order:
        // runtimeLifecycleLock -> cell.lifecycleLock -> bridge publication.
        // registerFromRuntime publishes the node, exact ActorRef mapping and
        // recovery ref as ONE transaction before runtime exposes the binding.
        runtime.attachSupervisorEscrowActor(actor, this, nodeId, parentId);
        return nodeId;
    }

    /** Install one trusted, opt-in CONTROL coordinator. No actor guest may call
     * this method and no second coordinator may compete for this ledger. */
    synchronized void installRecovery(SupervisorEscrowSubtreeRecoveryV1 coordinator) {
        runtime.requireSupervisorShutdownAuthority("install supervisor recovery CONTROL");
        Objects.requireNonNull(coordinator, "coordinator");
        if (recovery != null) throw new IllegalStateException("CONTROL coordinator already installed");
        // Host actors attached before coordinator installation cannot be
        // reconstructed from the ledger's IDs alone: their exact ActorRefs
        // were never registered with a cancellation owner. Reject rather
        // than grant a fake recoverable supervision tree.
        if (ledger.snapshot().size() != 1) {
            throw new IllegalStateException(
                    "CONTROL recovery must be installed before the first actor attachment");
        }
        recovery = coordinator;
    }

    boolean hasRecoveryCoordinatorFromRuntime() { return recovery != null; }

    /** Runtime-only monitor: acquire after the bridge and lifecycle locks.
     * The ledger is the single authority for DATA, failure and visibility. */
    Object ledgerPublicationMonitorFromRuntime() {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("CONTROL bridge lock required");
        return ledger;
    }

    private void requirePublicationTransaction() {
        if (!Thread.holdsLock(this) || !Thread.holdsLock(ledger)) {
            throw new IllegalStateException("CONTROL ledger publication transaction required");
        }
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

    /** Called only by ActorRuntime under its lifecycle locks AND the bridge
     * monitor, BEFORE publishing the ActorRuntime escrow binding. This must
     * register the node, exact ActorRef and recovery cancellation handle
     * atomically against terminal failure. */
    synchronized void registerFromRuntime(
            ActorRuntime.ActorRef<?> actor, long id, long parentId) {
        requirePublicationTransaction();
        Objects.requireNonNull(actor, "actor");
        ledger.registerChild(id, parentId, actor.generation());
        boolean mapped = false;
        try {
            if (ids.putIfAbsent(actor.id(), id) != null) {
                throw new IllegalStateException("actor already mapped to CONTROL");
            }
            mapped = true;
            SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
            if (coordinator != null) {
                coordinator.registerInheritedChildFromRuntime(id, actor);
            }
        } catch (RuntimeException | Error failure) {
            if (mapped) ids.remove(actor.id(), id);
            ledger.rollbackUnpublishedChild(id, parentId, actor.generation());
            throw failure;
        }
    }

    /** Runtime-only rollback if publishing the binding itself fails. Caller
     * must still hold lifecycleLock and bridge monitor. No live actor has
     * yet received the binding or a delivery opportunity. */
    synchronized void rollbackHostAttachFromRuntime(
            ActorRuntime.ActorRef<?> actor, long nodeId, long parentId) {
        rollbackInheritedChildFromRuntime(actor, nodeId, parentId);
    }

    /**
     * Runtime-only inherited CONTROL authority. The caller MUST hold this
     * bridge's monitor through both reservation and actor publication.
     * Neither a guest actor nor a supervisor-policy callback can mint IDs.
     */
    synchronized long reserveInheritedChildFromRuntime(
            ActorRuntime.ActorRef<?> child, long parentNodeId, long parentGeneration) {
        requirePublicationTransaction();
        Objects.requireNonNull(child, "child");
        if (recovery == null) throw new IllegalStateException("CONTROL recovery coordinator required");
        if (!ledger.mayAdmit(parentNodeId, parentGeneration)) {
            throw new IllegalStateException("parent supervisor CONTROL is fenced or stale");
        }
        long nodeId = nextId.getAndIncrement();
        if (nodeId < 2) throw new IllegalStateException("CONTROL identities exhausted");
        ledger.registerChild(nodeId, parentNodeId, child.generation());
        boolean mapped = false;
        try {
            if (ids.putIfAbsent(child.id(), nodeId) != null) {
                throw new IllegalStateException("duplicate inherited CONTROL ActorRef");
            }
            mapped = true;
            SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
            if (coordinator != null) coordinator.registerInheritedChildFromRuntime(nodeId, child);
            return nodeId;
        } catch (RuntimeException | Error failure) {
            if (mapped) ids.remove(child.id(), nodeId);
            ledger.rollbackUnpublishedChild(nodeId, parentNodeId, child.generation());
            throw failure;
        }
    }

    /** Only permitted before runtime publishes the child to actors/parent/
     * group registries, while holding the same bridge publication monitor. */
    synchronized void rollbackInheritedChildFromRuntime(
            ActorRuntime.ActorRef<?> child, long nodeId,
            long parentNodeId) {
        requirePublicationTransaction();
        Objects.requireNonNull(child, "child");
        if (!Objects.equals(ids.get(child.id()), nodeId)) {
            throw new IllegalStateException("inherited CONTROL rollback identity mismatch");
        }
        ledger.rollbackUnpublishedChild(nodeId, parentNodeId, child.generation());
        SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
        try {
            if (coordinator != null) coordinator.rollbackInheritedChildFromRuntime(nodeId, child);
        } catch (RuntimeException | Error failure) {
            if (!ids.remove(child.id(), nodeId)) {
                failure.addSuppressed(new IllegalStateException("inherited CONTROL child mapping changed"));
            }
            throw failure;
        }
        if (!ids.remove(child.id(), nodeId)) {
            throw new IllegalStateException("inherited CONTROL child mapping changed");
        }
    }

    /** Called by ActorRuntime at the first non-cancellation terminal fault. */
    synchronized void failedFromRuntime(long id) {
        ledger.fail(id);
        // The ledger is fenced before the notification; the coordinator only
        // queues CONTROL work and may not run guest or child callbacks inline.
        SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
        if (coordinator != null) coordinator.failedFromRuntime(id);
    }

    /** Called by ActorRuntime after real actor finalization. */
    synchronized void finalizedFromRuntime(long id, boolean failed) {
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
