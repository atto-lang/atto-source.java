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
    synchronized long attach(ActorRuntime.ActorRef<?> actor, ActorRuntime.ActorRef<?> supervisor) {
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
        // Runtime invokes registerFromRuntime while holding the actor's
        // lifecycle lock, ensuring no actor activation or coordinator
        // cancellation can observe a half-attached CONTROL node.
        runtime.attachSupervisorEscrowActor(actor, this, nodeId, parentId);
        return nodeId;
    }

    boolean hasRecoveryCoordinatorFromRuntime() {
        return recovery != null;
    }

    /**
     * Only ActorRuntime calls this under runtimeLifecycleLock and the
     * spawning parent's lifecycleLock. The escrow ledger holds its monitor
     * across both CONTROL registration and runtime publication. Concurrent
     * supervisor failure therefore observes either the entire child or no
     * child. In particular, the coordinator's refs table is registered
     * before releasing the ledger monitor.
     */
    void registerSpawnedChildFromRuntime(
            ActorRuntime.ActorRef<?> parent,
            ActorRuntime.ActorRef<?> child,
            long parentNodeId,
            long parentGeneration,
            Runnable publish) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(publish, "publish");
        SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
        if (coordinator == null) {
            throw new IllegalStateException(
                    "supervised actor nested spawn requires atomic CONTROL child registration and recovery coordinator");
        }
        if (!Objects.equals(ids.get(parent.id()), parentNodeId)) {
            throw new IllegalStateException("stale or foreign supervisor parent binding");
        }
        long childNodeId = nextId.getAndIncrement();
        if (childNodeId < 2) {
            throw new IllegalStateException("supervisor CONTROL identities exhausted");
        }
        ledger.registerChildAtomically(childNodeId, parentNodeId, child.generation(), () -> {
            if (!ledger.mayAdmit(parentNodeId, parentGeneration)) {
                throw new IllegalStateException("supervisor generation fenced before child publish");
            }
            if (ids.putIfAbsent(child.id(), childNodeId) != null) {
                throw new IllegalStateException("duplicate runtime child CONTROL identity");
            }
            boolean bound = false;
            boolean tracked = false;
            try {
                runtime.bindSpawnedSupervisorEscrowFromRuntime(child, this, childNodeId);
                bound = true;
                coordinator.registerSpawnedChildFromRuntime(childNodeId, child);
                tracked = true;
                publish.run();
            } catch (RuntimeException | Error failure) {
                // Every rollback step is independent. A failed coordinator
                // cleanup may not suppress runtime-binding or identity cleanup.
                try {
                    if (tracked) coordinator.rollbackSpawnedChildFromRuntime(childNodeId, child);
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    if (bound) runtime.rollbackSpawnedSupervisorEscrowFromRuntime(
                            child, this, childNodeId);
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                } finally {
                    ids.remove(child.id(), childNodeId);
                }
                throw failure;
            }
        });
    }

    /** Install one trusted, opt-in CONTROL coordinator. No actor guest may call
     * this method and no second coordinator may compete for this ledger. */
    synchronized void installRecovery(SupervisorEscrowSubtreeRecoveryV1 coordinator) {
        runtime.requireSupervisorShutdownAuthority("install supervisor recovery CONTROL");
        Objects.requireNonNull(coordinator, "coordinator");
        if (recovery != null) throw new IllegalStateException("CONTROL coordinator already installed");
        // Late installation would leave already-attached refs absent from the
        // coordinator cancellation index. Require an empty attachment set.
        if (!ids.isEmpty()) {
            throw new IllegalStateException(
                    "CONTROL coordinator must install before first actor attachment");
        }
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

    /**
     * Runtime-only host attachment transaction. The ledger monitor orders
     * registration, coordinator tracking and ActorRuntime binding against
     * failure fencing; no third-party guest callbacks execute here.
     */
    void registerFromRuntime(
            ActorRuntime.ActorRef<?> actor, long id, long parentId, Runnable publish) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(publish, "publish");
        ledger.registerChildAtomically(id, parentId, actor.generation(), () -> {
            if (ids.putIfAbsent(actor.id(), id) != null) {
                throw new IllegalStateException("actor CONTROL identity already exists");
            }
            SupervisorEscrowSubtreeRecoveryV1 coordinator = recovery;
            boolean tracked = false;
            try {
                if (coordinator != null) {
                    coordinator.registerSpawnedChildFromRuntime(id, actor);
                    tracked = true;
                }
                publish.run();
            } catch (RuntimeException | Error failure) {
                try {
                    if (tracked) coordinator.rollbackSpawnedChildFromRuntime(id, actor);
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                } finally {
                    ids.remove(actor.id(), id);
                }
                throw failure;
            }
        });
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

    int copyFencedSubtreeDeepestFirst(long id, long[] output) {
        return ledger.copyFencedSubtreeDeepestFirst(id, output);
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
