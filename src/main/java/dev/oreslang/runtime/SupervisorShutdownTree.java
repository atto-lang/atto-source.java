package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trusted-host supervisor shutdown substrate. This does not enable source
 * `define supervisor` or implement the guest checked-effect lowering.
 *
 * <p>Hook invocations are serialized through a shared CONTROL executor.
 * It MUST enqueue rather than execute inline, and MUST NOT use actor carriers.
 * Source compiler CONTROL admission and physical arena-loan quiescence remain
 * independent release gates.</p>
 */
public final class SupervisorShutdownTree {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONTROL_EVENTS = MAX_NODES * 16;

    public enum Reason { NORMAL, REQUESTED, PARENT_EXIT, RUNTIME_EXIT }

    public interface Hooks {
        default OresFuture<Void> onShutdown(Reason reason) {
            return OresFuture.completed(null);
        }

        /** After child supervisors and owned actor DONE barriers have settled. */
        default OresFuture<Void> onStopped(Reason reason) {
            return OresFuture.completed(null);
        }
    }

    public final class Supervisor {
        private final String name;
        private final long supervisorId;
        private final java.util.concurrent.atomic.AtomicReference<SupervisorLifecycleFsm.State>
                lifecycle = new java.util.concurrent.atomic.AtomicReference<>(
                        SupervisorLifecycleFsm.State.initial());
        private final ActorRuntime.ActorGroup group;
        private final Hooks hooks;
        private final int depth;
        private final List<Supervisor> children = new ArrayList<>();
        // Strong refs are kept only until actor shutdown actually settles.
        private volatile List<ActorRuntime.ActorRef<?>> shutdownWorkers = List.of();
        private final OresFuture<Void> terminated = new OresFuture<>(() -> false, () -> { });

        private Supervisor(String name, ActorRuntime.ActorGroup group, Hooks hooks,
                int depth, long supervisorId) {
            this.name = Objects.requireNonNull(name, "name");
            this.supervisorId = supervisorId;
            if (name.isBlank()) throw new IllegalArgumentException("empty supervisor name");
            this.group = Objects.requireNonNull(group, "group");
            this.hooks = Objects.requireNonNull(hooks, "hooks");
            this.depth = depth;
        }

        public String name() { return name; }
        public long supervisorId() { return supervisorId; }

        /** Immutable state observation; no guest hooks are invoked. */
        public SupervisorLifecycleFsm.State lifecycle() {
            runtime.requireSupervisorShutdownAuthority("inspect supervisor lifecycle");
            return lifecycle.get();
        }

        private void transition(SupervisorLifecycleFsm.Event event) {
            synchronized (treeLock) {
                lifecycle.updateAndGet(state -> SupervisorLifecycleFsm.reduce(state, event));
            }
        }

        /**
         * Noncancellable graceful-completion outcome. Failed or timed-out
         * results must never be interpreted as physical cleanup receipts.
         */
        public OresFuture<Void> terminated() { return terminated; }

        public Supervisor addChild(String childName, ActorRuntime.ActorGroup childGroup, Hooks childHooks) {
            return SupervisorShutdownTree.this.addChild(this, childName, childGroup, childHooks);
        }
    }

    /** Coherent tree snapshot. Logical stop never implies physical reclamation. */
    public record NodeStatus(
            long id, Long parentId, String name, SupervisorLifecycleFsm.State lifecycle) {}
    public record TreeStatus(
            List<NodeStatus> nodes,
            boolean allLogicallyStopped,
            boolean allPhysicallyReclaimed,
            boolean hasFailures,
            boolean hasDeadlineOverruns) {
        public TreeStatus { nodes = List.copyOf(nodes); }
    }

    private static void statuses(Supervisor node, Long parentId, List<NodeStatus> out) {
        out.add(new NodeStatus(node.supervisorId, parentId, node.name, node.lifecycle.get()));
        for (Supervisor child : node.children) statuses(child, node.supervisorId, out);
    }

    /** Snapshot under the tree registration/shutdown fence. */
    public TreeStatus status() {
        runtime.requireSupervisorShutdownAuthority("inspect supervisor tree status");
        synchronized (treeLock) {
            if (root == null) throw new IllegalStateException("supervisor root missing");
            List<NodeStatus> nodes = new ArrayList<>(supervisorCount);
            statuses(root, null, nodes);
            return new TreeStatus(nodes,
                    nodes.stream().allMatch(n -> n.lifecycle.logicallyStopped()),
                    nodes.stream().allMatch(n -> n.lifecycle.phase()
                            == SupervisorLifecycleFsm.Phase.RECLAIMED),
                    nodes.stream().anyMatch(n -> n.lifecycle.failed()),
                    nodes.stream().anyMatch(n -> n.lifecycle.deadlineExpired()));
        }
    }

    private final ActorRuntime runtime;
    private final Executor control;
    private final Object treeLock = new Object();
    private final Set<ActorRuntime.ActorGroup> boundGroups =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object queueLock = new Object();
    // Check actual synchronous invocation, not merely the thread identity:
    // a legitimate event loop may dequeue later on the submission thread.
    private final ThreadLocal<Boolean> insideSubmission = new ThreadLocal<>();
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private final AtomicBoolean emergency = new AtomicBoolean();
    private boolean draining;
    private boolean dispatchFailed;
    private Supervisor root;
    private int supervisorCount;
    private boolean shuttingDown;

    public SupervisorShutdownTree(ActorRuntime runtime, Executor serialControlExecutor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.control = Objects.requireNonNull(serialControlExecutor, "serialControlExecutor");
    }

    public Supervisor setRoot(String name, ActorRuntime.ActorGroup group, Hooks hooks) {
        runtime.requireSupervisorShutdownAuthority("install supervisor shutdown root");
        synchronized (treeLock) {
            if (root != null || shuttingDown) {
                throw new IllegalStateException("supervisor root already installed or stopping");
            }
            Supervisor next = new Supervisor(name, group, hooks, 1, 1L);
            verifyGroup(group);
            group.bindSupervisorShutdownTree(this);
            boundGroups.add(group);
            supervisorCount = 1;
            root = next;
            return next;
        }
    }

    private Supervisor addChild(
            Supervisor parent, String name, ActorRuntime.ActorGroup group, Hooks hooks) {
        runtime.requireSupervisorShutdownAuthority("register supervisor shutdown child");
        synchronized (treeLock) {
            if (shuttingDown) throw new IllegalStateException("supervisor tree is stopping");
            if (!belongsToTree(parent)) {
                throw new IllegalArgumentException("parent is not in this supervisor tree");
            }
            if (parent.depth >= MAX_DEPTH || supervisorCount >= MAX_NODES) {
                throw new IllegalStateException("supervisor tree capacity exceeded");
            }
            Supervisor child = new Supervisor(
                    name, group, hooks, parent.depth + 1, (long) supervisorCount + 1L);
            verifyGroup(group);
            if (boundGroups.contains(group)) {
                throw new IllegalArgumentException("ActorGroup already belongs to this supervisor");
            }
            group.bindSupervisorShutdownTree(this);
            boundGroups.add(group);
            parent.children.add(child);
            supervisorCount++;
            return child;
        }
    }

    private boolean belongsToTree(Supervisor candidate) {
        if (candidate == null || root == null) return false;
        return contains(root, candidate);
    }

    private static boolean contains(Supervisor node, Supervisor candidate) {
        if (node == candidate) return true;
        for (Supervisor child : node.children) {
            if (contains(child, candidate)) return true;
        }
        return false;
    }

    private void verifyGroup(ActorRuntime.ActorGroup group) {
        Objects.requireNonNull(group, "group");
        if (!group.ownedBy(runtime)) {
            throw new IllegalArgumentException("supervised group belongs to another runtime");
        }
        if (group.closed()) throw new IllegalStateException("cannot supervise closed ActorGroup");
    }

    /**
     * Freeze tree membership and managed-worker admission before calling
     * any hook. A conflicting spawn must fail management and cancel its newly
     * allocated actor instead of publishing an unowned child.
     */
    public OresFuture<Void> shutdown(Reason reason) {
        runtime.requireSupervisorShutdownAuthority("begin supervisor tree shutdown");
        Objects.requireNonNull(reason, "reason");
        final Supervisor target;
        Throwable fenceFailure = null;
        synchronized (treeLock) {
            if (root == null) throw new IllegalStateException("supervisor root is missing");
            target = root;
            if (shuttingDown) return target.terminated;
            // On any fence failure the tree MUST remain sealed, not retry
            // guest callbacks against a partially-fenced tree.
            shuttingDown = true;
            try {
                sealSubtree(target);
            } catch (Throwable failure) {
                fenceFailure = failure;
            }
        }
        if (fenceFailure != null) emergencyStop(fenceFailure);
        else dispatch(() -> start(target, reason));
        return target.terminated;
    }

    /**
     * A deadline creates a SEPARATE bounded failure observer. The underlying
     * terminated() future is never force-settled by a clock: a timeout does not
     * fabricate evidence that actor turns or guest hooks have ended.
     *
     * Watchdog scheduling failure also returns a failed bounded observer
     * after shutdown admission, rather than leaving a caller believing that
     * an unobserved shutdown was never started.
     */
    public OresFuture<Void> shutdown(
            Reason reason, Duration grace, ScheduledExecutorService watchdog) {
        runtime.requireSupervisorShutdownAuthority("begin timed supervisor tree shutdown");
        Objects.requireNonNull(grace, "grace");
        Objects.requireNonNull(watchdog, "watchdog");
        if (grace.isNegative()) throw new IllegalArgumentException("negative shutdown grace");
        long nanos;
        try {
            nanos = grace.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        OresFuture<Void> actual = shutdown(reason);
        OresFuture<Void> bounded = new OresFuture<>(() -> false, () -> { });
        AtomicBoolean observerSettled = new AtomicBoolean();
        final java.util.concurrent.ScheduledFuture<?> timer;
        try {
            timer = watchdog.schedule(() -> {
                TimeoutException deadline = new TimeoutException(
                        "supervisor tree shutdown deadline expired; physical cleanup unverified");
                if (actual.isDone() || !observerSettled.compareAndSet(false, true)) return;
                // Publish status before completing the observer: completion
                // callbacks may immediately inspect the tree on this thread.
                markDeadline();
                bounded.failFromRuntime(deadline);
                escalate(deadline);
            }, nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException rejected) {
            // A failed watchdog submission is itself a loss of bounded
            // shutdown observability. Publish failure before notifying the
            // bounded observer, whose callback may inspect tree status inline.
            // Do not forge a timer expiration or physical-release receipt.
            markCoordinatorFailed();
            bounded.failFromRuntime(rejected);
            escalate(rejected);
            return bounded;
        }
        actual.whenCompleteRuntime((ignored, failure) -> {
            timer.cancel(false);
            if (!observerSettled.compareAndSet(false, true)) return;
            if (failure == null) bounded.completeFromRuntime(null);
            else bounded.failFromRuntime(failure);
        });
        return bounded;
    }

    /**
     * An incompletely fenced node did not enter the shutdown automaton.
     * Treat its missed fence as a coordinator failure, not as a fabricated
     * successful shutdown or an invalid RUNNING -> deadline transition.
     * Pure for deterministic regression coverage without runtime reflection.
     */
    static SupervisorLifecycleFsm.Event deadlineEventFor(
            SupervisorLifecycleFsm.State current) {
        Objects.requireNonNull(current, "current");
        if (current.phase() == SupervisorLifecycleFsm.Phase.RUNNING
                || current.phase() == SupervisorLifecycleFsm.Phase.RESTARTING) {
            return new SupervisorLifecycleFsm.CoordinatorFailed(current.epoch());
        }
        return new SupervisorLifecycleFsm.DeadlineExpired(current.epoch());
    }

    private void markDeadline() {
        synchronized (treeLock) {
            List<Supervisor> nodes = new ArrayList<>();
            collect(root, nodes);
            for (Supervisor node : nodes) {
                SupervisorLifecycleFsm.State current = node.lifecycle.get();
                node.transition(deadlineEventFor(current));
            }
        }
    }

    private void markCoordinatorFailed() {
        synchronized (treeLock) {
            List<Supervisor> nodes = new ArrayList<>();
            collect(root, nodes);
            for (Supervisor node : nodes) {
                node.transition(new SupervisorLifecycleFsm.CoordinatorFailed(
                        node.lifecycle.get().epoch()));
            }
        }
    }

    private void sealSubtree(Supervisor node) {
        node.group.fenceWorkerAdmissions();
        node.shutdownWorkers = node.group.workers();
        node.transition(new SupervisorLifecycleFsm.ShutdownRequested(
                node.lifecycle.get().epoch(),
                node.children.stream().map(child -> child.supervisorId)
                        .collect(java.util.stream.Collectors.toSet())));
        for (Supervisor child : node.children) sealSubtree(child);
    }

    private static void collect(Supervisor node, List<Supervisor> out) {
        if (node == null) return;
        out.add(node);
        for (Supervisor child : node.children) collect(child, out);
    }

    /**
     * Serialize callbacks EVEN when the embedder accidentally supplies a
     * multi-worker executor. This is a CONTROL event queue, not a thread pool.
     * Any inline executor is rejected; callbacks must not run on a caller's
     * actor, producer, or timer stack.
     */
    private void dispatch(Runnable action) {
        boolean schedule = false;
        boolean overflow = false;
        synchronized (queueLock) {
            if (dispatchFailed) return;
            if (work.size() >= MAX_CONTROL_EVENTS) {
                overflow = true;
            } else {
                work.addLast(action);
                if (!draining) {
                    draining = true;
                    schedule = true;
                }
            }
        }
        if (overflow) {
            emergencyStop(new IllegalStateException(
                    "supervisor CONTROL event budget exhausted"));
            return;
        }
        if (!schedule) return;
        boolean previous = Boolean.TRUE.equals(insideSubmission.get());
        insideSubmission.set(true);
        try {
            control.execute(() -> {
                if (Boolean.TRUE.equals(insideSubmission.get())) {
                    emergencyStop(new IllegalStateException(
                            "supervisor CONTROL executor ran a callback inline"));
                } else {
                    drainQueue();
                }
            });
        } catch (RuntimeException rejected) {
            emergencyStop(rejected);
        } finally {
            if (previous) insideSubmission.set(true);
            else insideSubmission.remove();
        }
    }

    private void drainQueue() {
        for (;;) {
            Runnable next;
            synchronized (queueLock) {
                if (dispatchFailed) return;
                next = work.pollFirst();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
                emergencyStop(fatal);
                throw fatal;
            } catch (Throwable failure) {
                emergencyStop(failure);
            }
        }
    }

    /** No guest callbacks are run on the emergency / watchdog thread. */
    private void emergencyStop(Throwable failure) {
        if (!emergency.compareAndSet(false, true)) return;
        synchronized (queueLock) {
            dispatchFailed = true;
            work.clear();
            draining = false;
        }
        // The status plane records CONTROL loss, but must not fake hook
        // acknowledgments, logical termination, or native loan reclamation.
        markCoordinatorFailed();
        // An unavailable CONTROL dispatcher must not mark the root stopped:
        // exceptional root completion is deferred until every managed group
        // has actually retired. Guest hooks are not invoked during this
        // emergency fallback, and their omission is recorded as failure.
        OresFuture<Void> groups = escalate(failure);
        Supervisor target;
        synchronized (treeLock) {
            target = root;
        }
        if (target != null) {
            groups.whenCompleteRuntime((ignored, groupFailure) -> {
                IllegalStateException terminal = new IllegalStateException(
                        "supervisor CONTROL unavailable; hooks skipped, physical group close observed",
                        failure);
                if (groupFailure != null && groupFailure != failure) {
                    terminal.addSuppressed(groupFailure);
                }
                target.terminated.failFromRuntime(terminal);
            });
        }
    }

    /**
     * Escalation requests cooperative cancellation of the ORIGINAL
     * admission snapshot. closeAsync() alone is not sufficient: it is
     * idempotent and the group may already have entered graceful close.
     */
    private OresFuture<Void> escalate(Throwable reason) {
        List<Supervisor> nodes = new ArrayList<>();
        synchronized (treeLock) {
            collect(root, nodes);
        }
        // Reversed order prefers descendants but makes no physical
        // completion or hard-kill claim.
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Supervisor node = nodes.get(i);
            for (ActorRuntime.ActorRef<?> worker : node.shutdownWorkers) {
                try {
                    worker.cancel();
                } catch (RuntimeException failure) {
                    reason.addSuppressed(failure);
                }
            }
            try {
                node.group.closeAsync();
            } catch (RuntimeException failure) {
                reason.addSuppressed(failure);
            }
            // Once the actual group drain settles, no supervisor coordinator
            // needs to retain actor heap handles for later escalation.
            node.group.closedDone().whenCompleteRuntime((ignored, failure) ->
                    node.shutdownWorkers = List.of());
        }
        // Aggregate all group completion barriers instead of relying on
        // fail-fast Future.all: a failing child cannot hide a still-live
        // sibling's physical actor execution.
        OresFuture<Void> completed = new OresFuture<>(() -> false, () -> { });
        if (nodes.isEmpty()) {
            completed.completeFromRuntime(null);
            return completed;
        }
        AtomicInteger remaining = new AtomicInteger(nodes.size());
        java.util.concurrent.atomic.AtomicReference<Throwable> firstFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (Supervisor node : nodes) {
            node.group.closedDone().whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) firstFailure.compareAndSet(null, failure);
                if (remaining.decrementAndGet() == 0) {
                    Throwable terminal = firstFailure.get();
                    if (terminal == null) completed.completeFromRuntime(null);
                    else completed.failFromRuntime(terminal);
                }
            });
        }
        return completed;
    }

    private static OresFuture<Void> callShutdownHook(Supervisor node, Reason reason) {
        try {
            return Objects.requireNonNull(node.hooks.onShutdown(reason), "onShutdown returned null");
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return OresFuture.failed(failure);
        }
    }

    private static OresFuture<Void> callStoppedHook(Supervisor node, Reason reason) {
        try {
            return Objects.requireNonNull(node.hooks.onStopped(reason), "onStopped returned null");
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return OresFuture.failed(failure);
        }
    }

    private void start(Supervisor node, Reason reason) {
        final long epoch = node.lifecycle.get().epoch();
        List<Throwable> failures = new ArrayList<>();
        callShutdownHook(node, reason).whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    node.transition(new SupervisorLifecycleFsm.BeforeHookFinished(
                            epoch, failure != null));
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
        final long epoch = node.lifecycle.get().epoch();
        child.terminated.whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    node.transition(new SupervisorLifecycleFsm.ChildFinished(
                            epoch, child.supervisorId, failure != null));
                    stopChildren(node, reason, position - 1, failures);
                }));
        start(child, Reason.PARENT_EXIT);
    }

    /** Package-local initiation boundary for deterministic failure injection. */
    OresFuture<Void> closeWorkers(Supervisor node, List<Throwable> failures,
            java.util.function.Supplier<OresFuture<Void>> graceful,
            java.util.function.Supplier<OresFuture<Void>> fallback) {
        final long epoch = node.lifecycle.get().epoch();
        try {
            return graceful.get();
        } catch (Exception failure) {
            failures.add(failure);
            node.transition(new SupervisorLifecycleFsm.CoordinatorFailed(epoch));
            try {
                return fallback.get();
            } catch (Exception secondFailure) {
                failures.add(secondFailure);
                throw secondFailure;
            }
        }
    }

    private void stopWorkers(Supervisor node, Reason reason, List<Throwable> failures) {
        final long epoch = node.lifecycle.get().epoch();
        OresFuture<Void> done;
        try {
            done = closeWorkers(node, failures,
                    node.group::gracefulCloseAsync, node.group::closeAsync);
        } catch (Exception failure) {
            // A failed initiation is NOT a physical quiescence receipt.
            // Stop the coordinator so no parent treats this as ChildFinished.
            emergencyStop(failure);
            return;
        }
        done.whenCompleteRuntime((ignored, failure) ->
                dispatch(() -> {
                    if (failure != null) failures.add(failure);
                    node.transition(new SupervisorLifecycleFsm.ActorsFinished(
                            epoch, failure != null));
                    node.shutdownWorkers = List.of();
                    callStoppedHook(node, reason).whenCompleteRuntime((result, hookFailure) ->
                            dispatch(() -> {
                                if (hookFailure != null) failures.add(hookFailure);
                                node.transition(new SupervisorLifecycleFsm.AfterHookFinished(
                                        epoch, hookFailure != null));
                                if (failures.isEmpty()) {
                                    node.terminated.completeFromRuntime(null);
                                } else {
                                    IllegalStateException aggregate = new IllegalStateException(
                                            "supervisor " + node.name + " shutdown failed");
                                    for (Throwable problem : failures) aggregate.addSuppressed(problem);
                                    node.terminated.failFromRuntime(aggregate);
                                }
                            }));
                }));
    }
}
