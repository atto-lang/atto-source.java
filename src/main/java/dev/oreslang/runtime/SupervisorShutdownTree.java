package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Trusted-host supervisor shutdown coordinator.
 *
 * <p>One CONTROL executor is shared by the tree; there are no threads or
 * schedulers per supervisor. The injected executor MUST enqueue work onto a
 * serialized CONTROL lane and must never run guest callbacks inline on an
 * actor, producer, or timer thread. This is a runtime substrate, not source
 * lowering of `define supervisor` or permission for guest code to invoke
 * host supervisor hooks.</p>
 *
 * <p>Shutdown is top-down for admission fencing/onShutdown and bottom-up
 * for finalization/onStopped. Siblings stop in reverse registration order.
 * A failed hook/actor cannot prevent the remaining subtree from draining.
 * A timeout is a failed outcome, NEVER proof of physical termination.</p>
 */
public final class SupervisorShutdownTree {
    public enum Reason { NORMAL, REQUESTED, PARENT_EXIT, RUNTIME_EXIT }

    public interface Hooks {
        default OresFuture<Void> onShutdown(Reason reason) {
            return OresFuture.completed(null);
        }

        /** Called only after child supervisors and managed actors have settled. */
        default OresFuture<Void> onStopped(Reason reason) {
            return OresFuture.completed(null);
        }
    }

    public final class Supervisor {
        private final String name;
        private final ActorRuntime.ActorGroup group;
        private final Hooks hooks;
        private final List<Supervisor> children = new ArrayList<>();
        // Capture before graceful group close clears its worker registry.
        // Retain for timeout escalation only until physical finalization.
        private volatile List<ActorRuntime.ActorRef<?>> shutdownWorkers = List.of();
        private final OresFuture<Void> terminated = new OresFuture<>(() -> false, () -> { });

        private Supervisor(String name, ActorRuntime.ActorGroup group, Hooks hooks) {
            this.name = Objects.requireNonNull(name, "name");
            this.group = Objects.requireNonNull(group, "group");
            this.hooks = Objects.requireNonNull(hooks, "hooks");
        }

        public String name() { return name; }

        /** Noncancellable observer; failure or timeout is not a clean shutdown. */
        public OresFuture<Void> terminated() { return terminated; }

        public Supervisor addChild(String childName, ActorRuntime.ActorGroup childGroup, Hooks childHooks) {
            return SupervisorShutdownTree.this.addChild(this, childName, childGroup, childHooks);
        }
    }

    private final ActorRuntime runtime;
    private final Executor control;
    private final Object treeLock = new Object();
    private final Set<ActorRuntime.ActorGroup> boundGroups =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Supervisor root;
    private boolean shuttingDown;

    public SupervisorShutdownTree(ActorRuntime runtime, Executor serialControlExecutor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.control = Objects.requireNonNull(serialControlExecutor, "serialControlExecutor");
    }

    public Supervisor setRoot(String name, ActorRuntime.ActorGroup group, Hooks hooks) {
        synchronized (treeLock) {
            if (root != null || shuttingDown) {
                throw new IllegalStateException("supervisor root already installed or stopping");
            }
            verifyGroup(group);
            root = new Supervisor(name, group, hooks);
            boundGroups.add(group);
            return root;
        }
    }

    private Supervisor addChild(
            Supervisor parent, String name, ActorRuntime.ActorGroup group, Hooks hooks) {
        synchronized (treeLock) {
            if (shuttingDown) throw new IllegalStateException("supervisor tree is stopping");
            if (!belongsToTree(parent)) {
                throw new IllegalArgumentException("parent is not in this supervisor tree");
            }
            verifyGroup(group);
            if (!boundGroups.add(group)) {
                throw new IllegalArgumentException("ActorGroup already belongs to a supervisor");
            }
            Supervisor child = new Supervisor(name, group, hooks);
            parent.children.add(child);
            return child;
        }
    }

    private boolean belongsToTree(Supervisor candidate) {
        if (candidate == null || root == null) return false;
        return contains(root, candidate);
    }

    private static boolean contains(Supervisor root, Supervisor candidate) {
        if (root == candidate) return true;
        for (Supervisor child : root.children) {
            if (contains(child, candidate)) return true;
        }
        return false;
    }

    private void verifyGroup(ActorRuntime.ActorGroup group) {
        Objects.requireNonNull(group, "group");
        if (!group.ownedBy(runtime)) {
            throw new IllegalArgumentException("supervised group belongs to another runtime");
        }
        if (group.closed()) throw new IllegalStateException("cannot supervise a closed ActorGroup");
    }

    /**
     * Fence admissions for the WHOLE tree before dispatching any hook. A
     * concurrent spawn completing its allocation after the fence must fail
     * management and cancel its worker. No worker or supervisor is orphaned.
     */
    public OresFuture<Void> shutdown(Reason reason) {
        Objects.requireNonNull(reason, "reason");
        final Supervisor target;
        synchronized (treeLock) {
            if (root == null) throw new IllegalStateException("supervisor root is missing");
            target = root;
            if (shuttingDown) return target.terminated;
            // The admission fence and the tree freeze share a linearization
            // point with child registration. Do not call guest hooks here.
            sealSubtree(target);
            shuttingDown = true;
        }
        dispatch(() -> start(target, reason));
        return target.terminated;
    }

    /**
     * Deadline applies to the WHOLE tree, not individually to each sibling.
     * A timed-out observer fails; the runtime must still retain all active
     * execution leases/arenas until physical finalization is verified.
     * The caller supplies one shared timer, not a timer thread per actor.
     */
    public OresFuture<Void> shutdown(
            Reason reason, Duration grace, ScheduledExecutorService watchdog) {
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(watchdog, "watchdog");
        if (grace.isNegative()) throw new IllegalArgumentException("negative shutdown grace");
        OresFuture<Void> done = shutdown(reason);
        var timeout = watchdog.schedule(() -> {
            if (done.isDone()) return;
            TimeoutException deadline = new TimeoutException(
                    "supervisor tree shutdown deadline expired; physical cleanup unverified");
            // Do not claim success or release memory on the timeout path.
            if (!done.failFromRuntime(deadline)) return;
            List<Supervisor> nodes = new ArrayList<>();
            synchronized (treeLock) { collect(root, nodes); }
            // Cancellation is an escalation request, not forceful lease
            // revocation. Runtime shutdown must still await physical exit.
            for (Supervisor node : nodes) {
                try {
                    for (ActorRuntime.ActorRef<?> worker : node.shutdownWorkers) {
                        worker.cancel();
                    }
                } catch (RuntimeException failure) {
                    deadline.addSuppressed(failure);
                }
                try {
                    node.group.closeAsync();
                } catch (RuntimeException failure) {
                    deadline.addSuppressed(failure);
                }
            }
        }, grace.toNanos(), TimeUnit.NANOSECONDS);
        done.whenCompleteRuntime((ignored, failure) -> timeout.cancel(false));
        return done;
    }

    private void sealSubtree(Supervisor supervisor) {
        supervisor.group.fenceWorkerAdmissions();
        supervisor.shutdownWorkers = supervisor.group.workers();
        for (Supervisor child : supervisor.children) sealSubtree(child);
    }

    private static void collect(Supervisor node, List<Supervisor> nodes) {
        nodes.add(node);
        for (Supervisor child : node.children) collect(child, nodes);
    }

    private void dispatch(Runnable task) {
        try {
            control.execute(task);
        } catch (RuntimeException rejected) {
            // A stopped CONTROL lane is a terminal failure, never a
            // fabricated clean-shutdown completion.
            Supervisor target = root;
            if (target != null) target.terminated.failFromRuntime(rejected);
        }
    }

    private static OresFuture<Void> callShutdownHook(Supervisor node, Reason reason) {
        try {
            return Objects.requireNonNull(
                    node.hooks.onShutdown(reason), "onShutdown returned null");
        } catch (Exception failure) {
            return OresFuture.failed(failure);
        }
    }

    private static OresFuture<Void> callStoppedHook(Supervisor node, Reason reason) {
        try {
            return Objects.requireNonNull(
                    node.hooks.onStopped(reason), "onStopped returned null");
        } catch (Exception failure) {
            return OresFuture.failed(failure);
        }
    }

    private void start(Supervisor node, Reason reason) {
        List<Throwable> failures = new ArrayList<>();
        callShutdownHook(node, reason).whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    stopChildren(node, reason, node.children.size() - 1, failures);
                }));
    }

    private void stopChildren(
            Supervisor node, Reason reason, int position, List<Throwable> failures) {
        if (position < 0) {
            stopWorkers(node, reason, failures);
            return;
        }
        Supervisor child = node.children.get(position);
        child.terminated.whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    stopChildren(node, reason, position - 1, failures);
                }));
        start(child, Reason.PARENT_EXIT);
    }

    private void stopWorkers(Supervisor node, Reason reason, List<Throwable> failures) {
        OresFuture<Void> groupDone;
        try {
            groupDone = node.group.gracefulCloseAsync();
        } catch (Exception failure) {
            failures.add(failure);
            // A failed initiation still attempts ordinary structured close.
            try {
                groupDone = node.group.closeAsync();
            } catch (Exception secondFailure) {
                failures.add(secondFailure);
                groupDone = OresFuture.failed(secondFailure);
            }
        }
        groupDone.whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    node.shutdownWorkers = List.of();
                    callStoppedHook(node, reason).whenCompleteRuntime((done, hookFailure) ->
                            dispatch(() -> {
                                if (hookFailure != null) failures.add(hookFailure);
                                if (failures.isEmpty()) {
                                    node.terminated.completeFromRuntime(null);
                                } else {
                                    IllegalStateException aggregate =
                                            new IllegalStateException(
                                                    "supervisor " + node.name + " shutdown failed");
                                    for (Throwable problem : failures) aggregate.addSuppressed(problem);
                                    node.terminated.failFromRuntime(aggregate);
                                }
                            }));
                }));
    }
}
