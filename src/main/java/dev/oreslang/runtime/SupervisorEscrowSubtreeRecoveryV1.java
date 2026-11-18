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
        DATA_ADMISSIONS_IN_FLIGHT, CHILD_STILL_RUNNING,
        PHYSICAL_RELEASE_UNVERIFIED, PARENT_UNAVAILABLE
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
        // The bridge MUST have registered the exact physical ActorRef
        // before publishing the runtime's CONTROL binding. Do not publish
        // an after-the-fact ref here: ancestor failure could observe a
        // ledger child without a cancellable runtime object.
        synchronized (lock) {
            if (refs.get(nodeId) != ref) {
                throw new IllegalStateException(
                        "host actor CONTROL attachment was not atomically published");
            }
        }
        return nodeId;
    }

    /** Must be invoked before the child is visible to any runtime registry.
     * The bridge's own publication monitor serializes this step against
     * supervisor failure, so CONTROL cannot observe a half-published child. */
    void registerInheritedChildFromRuntime(
            long nodeId, ActorRuntime.ActorRef<?> child) {
        Objects.requireNonNull(child, "child");
        synchronized (lock) {
            if (controlFault.get() != null || refs.containsKey(nodeId)) {
                throw new IllegalStateException("CONTROL child registration unavailable");
            }
            refs.put(nodeId, child);
        }
    }

    /** Reservation was never published; no terminal notification is allowed. */
    void rollbackInheritedChildFromRuntime(
            long nodeId, ActorRuntime.ActorRef<?> child) {
        synchronized (lock) {
            if (refs.get(nodeId) != child || refs.remove(nodeId) != child) {
                throw new IllegalStateException("CONTROL child rollback ref mismatch");
            }
        }
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
        // One accepted-but-still-settling DATA permit is sufficient to deny
        // reuse of the subtree. Java DONE and a test-only release oracle
        // cannot erase a logical admission that predates the crash fence.
        if (bridge.dataInFlightInSubtree(nodeId) != 0)
            return RestartBlocker.DATA_ADMISSIONS_IN_FLIGHT;
        // Report unacknowledged failures before inspecting release evidence,
        // independent of the snapshot's iteration order.
        for (var entry : states.entrySet()) {
            long id = entry.getKey();
            if (entry.getValue().pending() && (id == nodeId || isBelow(id, nodeId, states)))
                return RestartBlocker.PENDING_FAILURE;
        }
        for (long id : states.keySet()) {
            if (id != nodeId && !isBelow(id, nodeId, states)) continue;
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
        synchronized (lock) {
            if (successorIds.containsKey(nodeId) ||
                    issued.stream().anyMatch(p -> p.oldNodeId == nodeId)) {
                throw new IllegalStateException("restart already reserved or committed");
            }
            issued.add(permit);
        }
        return permit;
    }

    long attachReplacement(RestartPermit permit, ActorRuntime.ActorRef<?> replacement,
            ActorRuntime.ActorRef<?> liveParent) {
        runtime.requireSupervisorShutdownAuthority("commit supervisor replacement");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(replacement, "replacement");
        if (restartBlocker(permit.oldNodeId) != RestartBlocker.READY)
            throw new IllegalStateException("subtree not physically verified");
        if (replacement.generation() <= permit.oldGeneration)
            throw new IllegalStateException("replacement generation not newer");
        long actualParent = liveParent == null ? 1L : bridge.nodeId(liveParent);
        long expectedParent;
        synchronized (lock) {
            expectedParent = successorIds.getOrDefault(
                    permit.oldParentId, permit.oldParentId);
            if (!issued.remove(permit) || permit.used || actualParent != expectedParent)
                throw new IllegalStateException("stale, reused or misparented restart permit");
            // Reserve the successor slot BEFORE releasing lock. Until the new
            // registration commits, -1 means permanently fenced/incomplete.
            // No second permit may race in the gap between validation and attach.
            permit.used = true;
            successorIds.put(permit.oldNodeId, -1L);
        }
        long newNode = attach(replacement, liveParent);
        synchronized (lock) { successorIds.put(permit.oldNodeId, newNode); }
        return newNode;
    }

    Throwable controlFault() {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor CONTROL failure");
        return controlFault.get();
    }
}
