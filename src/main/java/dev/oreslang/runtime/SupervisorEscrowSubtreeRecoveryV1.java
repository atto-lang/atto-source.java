package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in runtime-owned supervisor crash containment. An actor failure enqueues
 * cancellation of every registered supervisory descendant onto a distinct
 * trusted CONTROL executor. No guest policy or synchronous child stop executes
 * in the dying actor's lifecycle lock. The escrow ledger remains the only
 * authority for failure notices, epochs and admission fences.
 *
 * This does not certify physical native/FFI release or auto-create new actors.
 */
final class SupervisorEscrowSubtreeRecoveryV1 {
    /** Only a separately authenticated runtime physical-release source can
     * approve a replacement. A Java done() alone is intentionally insufficient. */
    @FunctionalInterface
    interface PhysicalReleaseVerifier {
        boolean independentlyReclaimed(ActorRuntime.ActorRef<?> ref);
    }

    enum RestartBlocker {
        READY, CONTROL_FAILED, UNKNOWN_NODE, ALREADY_REPLACED, STILL_ALIVE, PENDING_FAILURE,
        CHILD_STILL_RUNNING, PHYSICAL_RELEASE_UNVERIFIED, PARENT_UNAVAILABLE, RESTART_ESCALATED
    }

    static final class RestartPermit {
        private final long oldNodeId;
        private final long oldGeneration;
        private final long oldParentId;
        private boolean used;
        private RestartPermit(long nodeId, long generation, long parentId) {
            oldNodeId = nodeId;
            oldGeneration = generation;
            oldParentId = parentId;
        }
    }

    /**
     * Opt-in permanent ONE_FOR_ONE policy for one explicitly registered
     * guardian child and its successors. The circuit owns only restart
     * admission; escrow remains the sole terminal/physical authority.
     */
    private static final class RootRestartPolicy {
        final ActorSupervisorRestartCircuit circuit;
        final java.util.function.LongSupplier clock;
        ActorSupervisorRestartCircuit.RestartPlan plan;
        long decidedAt;
        RootRestartPolicy(ActorSupervisorRestartCircuit.Config config,
                java.util.function.LongSupplier clock, long generation) {
            if (config.childCount() != 1)
                throw new IllegalArgumentException("root restart policy requires exactly one child");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.circuit = new ActorSupervisorRestartCircuit(config);
            circuit.admitGeneration(0, generation, clock.getAsLong());
        }
    }

    // Guarded by lock. A policy follows the successor, not the physical node.
    private final Map<Long, RootRestartPolicy> rootPolicies = new HashMap<>();

    private final ActorRuntime runtime;
    private final SupervisorFailureEscrowRuntimeBridgeV1 bridge;
    private final Executor control;
    private final PhysicalReleaseVerifier physicalRelease;
    private final Map<Long, ActorRuntime.ActorRef<?>> refs = new HashMap<>();
    private final Object lock = new Object();
    private final ArrayDeque<Long> pendingRoots = new ArrayDeque<>();
    private final Set<Long> queuedRoots = new HashSet<>();
    private final Set<RestartPermit> issued = new HashSet<>();
    private final Map<Long, Long> successorIds = new HashMap<>();
    private final AtomicReference<Throwable> controlFault = new AtomicReference<>();
    private final ThreadLocal<Boolean> submitting = new ThreadLocal<>();
    private boolean scheduled;

    SupervisorEscrowSubtreeRecoveryV1(
            ActorRuntime runtime,
            SupervisorFailureEscrowRuntimeBridgeV1 bridge,
            Executor control,
            PhysicalReleaseVerifier physicalRelease) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.control = Objects.requireNonNull(control, "control");
        this.physicalRelease = Objects.requireNonNull(physicalRelease, "physicalRelease");
        runtime.requireSupervisorShutdownAuthority("create supervisor CONTROL recovery");
        bridge.installRecovery(this);
    }

    /** Attach-before-activation is enforced by ActorRuntime, not by a guest. */
    long attach(ActorRuntime.ActorRef<?> ref, ActorRuntime.ActorRef<?> supervisor) {
        runtime.requireSupervisorShutdownAuthority("attach supervisor recovery actor");
        if (controlFault.get() != null) throw new IllegalStateException("CONTROL fault");
        // Never hold the coordinator monitor while taking ActorRuntime's
        // admission/actor lifecycle locks (faults acquire them in reverse).
        long nodeId = bridge.attach(ref, supervisor);
        synchronized (lock) { refs.put(nodeId, ref); }
        return nodeId;
    }

    /**
     * Host-only explicit opt-in. As with attach(), callers must not activate
     * the actor until registration returns. This is a single-child policy,
     * not a shared restart budget across multiple guardian children.
     */
    long attachRestartManagedRoot(ActorRuntime.ActorRef<?> ref,
            ActorSupervisorRestartCircuit.Config config) {
        return attachRestartManagedRoot(ref, config, System::nanoTime);
    }

    // Package-private trusted clock injection for deterministic host tests.
    long attachRestartManagedRoot(ActorRuntime.ActorRef<?> ref,
            ActorSupervisorRestartCircuit.Config config,
            java.util.function.LongSupplier clock) {
        runtime.requireSupervisorShutdownAuthority("attach restart-managed guardian child");
        Objects.requireNonNull(ref, "ref");
        var policy = new RootRestartPolicy(Objects.requireNonNull(config, "config"),
                clock, ref.generation());
        long nodeId = attach(ref, null);
        synchronized (lock) { rootPolicies.put(nodeId, policy); }
        return nodeId;
    }

    /** Called only from the already-fenced runtime failure publication path. */
    void failedFromRuntime(long failedNode) {
        boolean submit = false;
        synchronized (lock) {
            if (queuedRoots.add(failedNode)) pendingRoots.addLast(failedNode);
            if (!scheduled && controlFault.get() == null) {
                scheduled = true;
                submit = true;
            }
        }
        if (submit) submitControl();
    }

    private void submitControl() {
        // An inline executor is forbidden: its body would run while actor
        // lifecycleLock is held by the failing actor. Fail closed instead.
        submitting.set(Boolean.TRUE);
        try {
            control.execute(() -> {
                if (Boolean.TRUE.equals(submitting.get())
                        || ActorRuntime.inActorExecution()
                        || ActorRuntime.inActorGroupMailmanExecution()) {
                    controlFault.compareAndSet(null, new IllegalStateException(
                            "supervision CONTROL executed inline or on an actor carrier"));
                    return;
                }
                drain();
            });
        } catch (RuntimeException | Error rejected) {
            controlFault.compareAndSet(null, rejected);
        } finally {
            submitting.remove();
        }
    }

    private void drain() {
        while (controlFault.get() == null) {
            final long root;
            synchronized (lock) {
                Long next = pendingRoots.pollFirst();
                if (next == null) {
                    scheduled = false;
                    return;
                }
                root = next;
                queuedRoots.remove(root);
            }
            try {
                cancelRegisteredSubtree(root);
            } catch (RuntimeException | Error failure) {
                controlFault.compareAndSet(null, failure);
            }
        }
    }

    private void cancelRegisteredSubtree(long root) {
        Map<Long, SupervisorFailureEscrowV1.NodeStatus> states = bridge.snapshot();
        if (!states.containsKey(root)) throw new IllegalStateException("unknown failing supervisor");
        List<Long> descendants = new ArrayList<>();
        for (long candidate : states.keySet()) {
            if (candidate == root || isBelow(candidate, root, states)) descendants.add(candidate);
        }
        descendants.sort(Comparator.comparingInt(
                (Long id) -> depth(id, states)).reversed());
        for (long id : descendants) {
            ActorRuntime.ActorRef<?> ref;
            synchronized (lock) { ref = refs.get(id); }
            if (ref == null) throw new IllegalStateException("missing registered CONTROL ActorRef");
            if (!ref.done().isDone()) {
                // cancel() is a nonblocking structured request. stop() would
                // park a host worker for up to CLOSE_WAIT_NANOS and is forbidden.
                runtime.cancel(ref);
            }
        }
    }

    private static boolean isBelow(long node, long ancestor,
            Map<Long, SupervisorFailureEscrowV1.NodeStatus> states) {
        long parent = states.get(node).parentId();
        int visited = 0;
        while (parent != 0) {
            if (parent == ancestor) return true;
            if (++visited > SupervisorFailureEscrowV1.MAX_DEPTH)
                throw new IllegalStateException("supervision parent cycle");
            var value = states.get(parent);
            if (value == null) throw new IllegalStateException("missing supervisor ancestor");
            parent = value.parentId();
        }
        return false;
    }

    private static int depth(long node,
            Map<Long, SupervisorFailureEscrowV1.NodeStatus> states) {
        int depth = 0;
        long id = node;
        while (id != 0) {
            if (++depth > SupervisorFailureEscrowV1.MAX_DEPTH)
                throw new IllegalStateException("supervision depth exceeded");
            var current = states.get(id);
            if (current == null) throw new IllegalStateException("unknown parent");
            id = current.parentId();
        }
        return depth;
    }

    /** Conservative preflight: no restart without all descendant termination,
     * ACK of all affected failures and separate physical-release evidence. */
    RestartBlocker restartBlocker(long nodeId) {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor restart safety");
        if (controlFault.get() != null) return RestartBlocker.CONTROL_FAILED;
        var states = bridge.snapshot();
        var root = states.get(nodeId);
        if (root == null || nodeId == 1) return RestartBlocker.UNKNOWN_NODE;
        synchronized (lock) {
            if (successorIds.containsKey(nodeId)) return RestartBlocker.ALREADY_REPLACED;
            RootRestartPolicy policy = rootPolicies.get(nodeId);
            if (policy != null && policy.circuit.escalated())
                return RestartBlocker.RESTART_ESCALATED;
        }
        if (root.alive()) return RestartBlocker.STILL_ALIVE;
        if (root.parentId() != 1) {
            long currentParent;
            synchronized (lock) {
                currentParent = successorIds.getOrDefault(root.parentId(), root.parentId());
            }
            var parent = states.get(currentParent);
            if (parent == null || !parent.alive() || parent.fenced())
                return RestartBlocker.PARENT_UNAVAILABLE;
        }
        for (var entry : states.entrySet()) {
            long id = entry.getKey();
            if (id != nodeId && !isBelow(id, nodeId, states)) continue;
            if (entry.getValue().pending()) return RestartBlocker.PENDING_FAILURE;
            ActorRuntime.ActorRef<?> ref;
            synchronized (lock) { ref = refs.get(id); }
            if (ref == null || !ref.done().isDone()) return RestartBlocker.CHILD_STILL_RUNNING;
            if (!physicalRelease.independentlyReclaimed(ref))
                return RestartBlocker.PHYSICAL_RELEASE_UNVERIFIED;
        }
        return RestartBlocker.READY;
    }

    /** Reserve a single-use generation-fenced restart. No new actor is started
     * here. The host must separately attach a new runtime-minted ActorRef. */
    RestartPermit reserveRestart(long nodeId) {
        runtime.requireSupervisorShutdownAuthority("reserve supervisor replacement");
        if (restartBlocker(nodeId) != RestartBlocker.READY)
            throw new IllegalStateException("subtree is not safely reclaimed");
        var old = bridge.snapshot().get(nodeId);
        var permit = new RestartPermit(nodeId, old.generation(), old.parentId());
        final RootRestartPolicy policy;
        synchronized (lock) { policy = rootPolicies.get(nodeId); }
        // The trusted clock is sampled outside the recovery monitor.
        long now = policy == null ? 0L : policy.clock.getAsLong();
        synchronized (lock) {
            if (controlFault.get() != null || successorIds.containsKey(nodeId) ||
                    issued.stream().anyMatch(p -> p.oldNodeId == nodeId)) {
                throw new IllegalStateException("restart already reserved or committed");
            }
            if (policy != null) {
                // We have passed terminal ACK and independent physical release.
                // PERMANENT treats normal and abnormal exits identically.
                // Charge exactly once per old generation, not per retry.
                if (policy.plan == null) {
                    policy.plan = policy.circuit.onExit(0, old.generation(),
                            ActorSupervisorRestartCircuit.RestartPolicy.PERMANENT,
                            ActorSupervisorRestartCircuit.ExitClass.NORMAL,
                            ActorSupervisorRestartCircuit.Strategy.ONE_FOR_ONE, now);
                    policy.decidedAt = now;
                }
                if (policy.plan.kind() != ActorSupervisorRestartCircuit.DecisionKind.RESTART)
                    throw new IllegalStateException("restart circuit escalated; parent recovery required");
            }
            issued.add(permit);
        }
        return permit;
    }

    /**
     * Nonblocking host scheduler input; no sleep or new timer thread. The
     * actual attach path independently checks the circuit's elapsed-time gate.
     */
    long restartDelayRemainingNanos(RestartPermit permit) {
        runtime.requireSupervisorShutdownAuthority("inspect remaining restart backoff");
        Objects.requireNonNull(permit, "permit");
        final RootRestartPolicy policy;
        synchronized (lock) {
            if (!issued.contains(permit) || permit.used || controlFault.get() != null)
                throw new IllegalStateException("stale restart permit or CONTROL fault");
            policy = rootPolicies.get(permit.oldNodeId);
            if (policy == null) return 0L;
        }
        long now = policy.clock.getAsLong();
        synchronized (lock) {
            if (!issued.contains(permit) || permit.used || controlFault.get() != null
                    || policy.circuit.escalated() || policy.plan == null)
                throw new IllegalStateException("restart permit no longer admitted");
            long elapsed = now - policy.decidedAt;
            if (elapsed < 0L) throw new IllegalStateException("restart clock regressed");
            return Math.max(0L, policy.plan.delayNanos() - elapsed);
        }
    }

    long attachReplacement(RestartPermit permit, ActorRuntime.ActorRef<?> replacement,
            ActorRuntime.ActorRef<?> liveParent) {
        runtime.requireSupervisorShutdownAuthority("commit supervisor replacement");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(replacement, "replacement");
        // Reject foreign/replayed permits before running a potentially
        // expensive external physical-release verifier.
        synchronized (lock) {
            if (!issued.contains(permit) || permit.used
                    || successorIds.containsKey(permit.oldNodeId)) {
                throw new IllegalStateException("stale, foreign or reused restart permit");
            }
        }
        if (restartBlocker(permit.oldNodeId) != RestartBlocker.READY)
            throw new IllegalStateException("subtree not physically verified");
        if (replacement.generation() <= permit.oldGeneration)
            throw new IllegalStateException("replacement generation not newer");
        long actualParent = liveParent == null ? 1L : bridge.nodeId(liveParent);
        long expectedParent;
        final RootRestartPolicy policy;
        synchronized (lock) { policy = rootPolicies.get(permit.oldNodeId); }
        long now = policy == null ? 0L : policy.clock.getAsLong();
        synchronized (lock) {
            expectedParent = successorIds.getOrDefault(
                    permit.oldParentId, permit.oldParentId);
            // Validation must precede mutation. A denied misparented
            // attempt must not consume the legitimate parent's permit.
            // Recheck after the external verifier and reserve atomically.
            if (controlFault.get() != null || !issued.contains(permit)
                    || permit.used || successorIds.containsKey(permit.oldNodeId)
                    || actualParent != expectedParent) {
                throw new IllegalStateException("stale, reused or misparented restart permit");
            }
            if (policy != null) {
                // Early timing denial must leave the permit intact. If actor
                // attachment later fails, successor -1 keeps the old node
                // permanently fenced; no second attempt spends the budget.
                policy.circuit.admitGeneration(0, replacement.generation(), now);
            }
            issued.remove(permit);
            // Reserve the successor slot BEFORE releasing lock. Until the new
            // registration commits, -1 means permanently fenced/incomplete.
            // No second permit may race in the gap between validation and attach.
            permit.used = true;
            successorIds.put(permit.oldNodeId, -1L);
        }
        long newNode = attach(replacement, liveParent);
        synchronized (lock) {
            successorIds.put(permit.oldNodeId, newNode);
            if (policy != null) {
                rootPolicies.remove(permit.oldNodeId);
                policy.plan = null;
                policy.decidedAt = 0L;
                rootPolicies.put(newNode, policy);
            }
        }
        return newNode;
    }

    Throwable controlFault() {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor CONTROL failure");
        return controlFault.get();
    }
}
