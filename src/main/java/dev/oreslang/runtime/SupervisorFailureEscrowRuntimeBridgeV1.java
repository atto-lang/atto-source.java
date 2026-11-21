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

    /** Called only by ActorRuntime while publishing a new trusted binding. */
    void registerFromRuntime(ActorRuntime.ActorRef<?> actor, long id, long parentId) {
        ledger.registerChild(id, parentId, actor.generation());
    }

    /** Called by ActorRuntime at the first non-cancellation terminal fault. */
    void failedFromRuntime(long id) {
        ledger.fail(id);
    }

    /** Called by ActorRuntime after real actor finalization. */
    void finalizedFromRuntime(long id, boolean failed) {
        if (failed) ledger.fail(id);
        else ledger.completeNormally(id);
    }

    /** Host admission guard: must be rechecked at the mailbox commit point. */
    boolean mayAdmitFromRuntime(long id, long generation) {
        return ledger.mayAdmit(id, generation);
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
