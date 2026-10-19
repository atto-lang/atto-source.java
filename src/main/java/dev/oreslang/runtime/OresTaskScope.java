package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Host/compiler-only structured ownership domain for lightweight Tasks.
 *
 * <p>Each Task has exactly one scope owner; child scopes are owned by their
 * parent. Cancelling a scope closes admission and requests cancellation of
 * all owned descendants, while close() joins physical Task quiescence.
 * Handles may be observed externally, but observation does not transfer
 * ownership. Guest task/actor lowering must NOT expose this host bridge
 * before actor-affine capture and quota checks are implemented.</p>
 *
 * <p>close() is a blocking host boundary: never invoke it from a guest
 * scheduler turn, actor dispatcher, or Task carrier. Use cancel() for
 * nonblocking cancellation requests.</p>
 */
public final class OresTaskScope implements AutoCloseable {
    // Null only for a compiler/actor-owned scope whose frames are admitted by
    // OresScheduler and whose code/resource lease is supplied explicitly.
    private final AsyncRuntime runtime;
    private final OresTaskScope parent;
    private final Object gate = new Object();
    // All members of one owned tree share a strict admission/plan monitor.
    // Lock order: treeAdmissionGate -> individual scope.gate.
    private final Object treeAdmissionGate;

    /**
     * Reserve ownership before executable admission. The slot itself is
     * allocated/inserted before scheduler or AsyncRuntime submission, so a
     * HashSet growth failure cannot orphan a live Task.
     */
    private static final class OwnershipSlot {
        private OresTask<?> task;
    }

    // Deliberately non-final only for package/reflection fault-injection tests.
    // Production never replaces this set.
    private Set<OwnershipSlot> tasks = new HashSet<>();
    private final Set<OresTaskScope> childScopes = new HashSet<>();
    /** Directed cancellation observers; do not carry lifecycle ownership. */
    private final List<OresCancellationToken.Registration> externalCancellation =
            new ArrayList<>();
    private final CountDownLatch fullyJoined = new CountDownLatch(1);
    private volatile boolean cancelled;
    private boolean joining;
    private volatile Throwable closeFailure;

    public OresTaskScope(AsyncRuntime runtime) {
        this(Objects.requireNonNull(runtime, "runtime"), null);
    }

    /**
     * Compiler/runtime-only scope with no ordinary AsyncRuntime submission
     * authority. Actor-owned continuation frames use this form so there is no
     * second scheduling domain beside the actor's route/code authority.
     */
    static OresTaskScope compilerOwned() {
        return new OresTaskScope(null, null);
    }

    private OresTaskScope(AsyncRuntime runtime, OresTaskScope parent) {
        this.runtime = runtime;
        this.parent = parent;
        this.treeAdmissionGate = parent == null
                ? new Object() : parent.treeAdmissionGate;
    }

    public <T> OresTask<T> submit(AsyncRuntime.Task<T> work) {
        Objects.requireNonNull(work, "work");
        if (runtime == null) {
            throw new RejectedExecutionException(
                    "compiler-owned TaskScope has no ordinary AsyncRuntime admission");
        }
        // Allocate the actual physical-exit witness and Task shell before
        // entering the tree's admission lock. No executor runs at this point.
        AsyncRuntime.PreparedTask<T> prepared = runtime.prepareTask(() -> {
            if (cancelled) throw new java.util.concurrent.CancellationException(
                    "Task scope cancelled before host Task entry");
            return work.run();
        });
        OresTask<T> child = prepared.owner();
        OwnershipSlot slot = null;
        try {
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    ensureAdmitting();
                    slot = new OwnershipSlot();
                    slot.task = child;
                    if (!tasks.add(slot))
                        throw new IllegalStateException("duplicate Task ownership slot");
                    OwnershipSlot owned = slot;
                    // The host's physical-exit callback executes once AFTER
                    // the carrier exits (or admission is rejected). Remove
                    // its slot BEFORE publishing the getDone() quiescence
                    // fence. Generic Future listeners swallow exceptions and
                    // therefore cannot be authoritative ownership cleanup.
                    prepared.onPhysicalExit(() -> {
                        synchronized (gate) {
                            if (!tasks.remove(owned))
                                throw new IllegalStateException(
                                        "host Task owner slot missing at retirement");
                        }
                    });
                }
            }
        } catch (RuntimeException | Error rejected) {
            // A failed callback subscription may occur AFTER the owner slot
            // was inserted. No carrier exists yet: retire it and preserve
            // any secondary removal fault without losing the first error.
            if (slot != null) {
                synchronized (gate) {
                    discardUnadmittedSlot(slot, rejected);
                }
            }
            prepared.rejectUnadmitted();
            throw rejected;
        }

        try {
            // Executor entry (including inline turns) must not hold the
            // individual scope or tree-wide admission monitor.
            prepared.start();
        } catch (RuntimeException | Error rejected) {
            // The PreparedTask has already fenced pre-execution rejection.
            // Physical completion callback removes the owned slot.
            throw rejected;
        }

        if (cancelled) {
            Runnable notification = child.cancelDeferredNotification();
            if (notification != null) notification.run();
        }
        return child;
    }

    /**
     * Compiler-only continuation entry point. The Task's *result* may settle
     * on cancellation before its guest frame finishes; scope membership is
     * therefore retained until the scheduler's physical quiescence callback.
     *
     * The scheduler owns resumed turns and never parks a carrier on await.
     * Do not expose this method to guest source without the owning context's
     * capture, actor-affinity, permission and fuel admission checks.
     */
    <T> OresTask<T> startCompilerFrame(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        return startCompilerFrame(scheduler, frame, () -> { });
    }

    /**
     * Trusted host lifecycle seam for code-generation and external-resource
     * leases. The onPhysicalExit callback runs exactly once AFTER the final
     * guest carrier turn has unwound, not when the cancelable result settles.
     * A suspends call uses its parent's existing Task and does not re-enter
     * this admission path. A child task needs its own independent code pin.
     *
     * The caller must acquire its pin BEFORE admission and must preserve it
     * on all preparation, cancellation and close paths. The callback must
     * be nonblocking, host-only and not execute guest code. The code-image
     * owner must independently check its active pin counts before unload.
     */
    <T> OresTask<T> startCompilerFrame(
            OresScheduler scheduler, OresScheduler.Task<T> frame,
            Runnable onPhysicalExit) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(onPhysicalExit, "onPhysicalExit");

        OwnershipSlot slot = null;
        OresFuture<Void> exited = null;
        OresTask<T> child = null;
        try {
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    ensureAdmitting();
                    // All potentially allocating Task ownership work occurs
                    // before admitting executable work to the scheduler.
                    slot = new OwnershipSlot();
                    exited = new OresFuture<>();
                    child = OresTask.<T>preAdmitted(exited);
                    slot.task = child;
                    if (!tasks.add(slot))
                        throw new IllegalStateException("duplicate Task ownership slot");
                }
            }
        } catch (RuntimeException | Error rejected) {
            if (slot != null) {
                synchronized (gate) {
                    discardUnadmittedSlot(slot, rejected);
                }
            }
            releaseUnadmitted(onPhysicalExit, rejected);
            throw rejected;
        }

        final OwnershipSlot owned = slot;
        final OresFuture<Void> exitFence = exited;
        final OresTask<T> shell = child;
        final OresFuture<T> completion;
        try {
            // Neither admission lock may be held during enqueue, guest turn,
            // synchronous scheduler callback or external resource release.
            completion = scheduler.start(resume -> {
                if (cancelled) throw new java.util.concurrent.CancellationException(
                        "Task scope cancelled before guest Task turn");
                return frame.resume(resume);
            }, () -> {
                // This is the scheduler's physical-exit callback. Release
                // the trusted generation pin and retire its owned slot
                // BEFORE publishing quiescence. Generic OresFuture waiters
                // swallow callback exceptions, so removing the slot from
                // a getDone()/exitFence listener would conceal a failed
                // retirement behind a successful physical fence.
                try {
                    onPhysicalExit.run();
                } catch (RuntimeException | Error releaseFailure) {
                    exitFence.failFromRuntime(releaseFailure);
                    return;
                }
                try {
                    synchronized (gate) {
                        if (!tasks.remove(owned))
                            throw new IllegalStateException(
                                    "physical Task owner slot missing at retirement");
                    }
                    exitFence.completeFromRuntime(null);
                } catch (RuntimeException | Error bookkeepingFailure) {
                    exitFence.failFromRuntime(bookkeepingFailure);
                }
            });
        } catch (RuntimeException | Error rejected) {
            // A rejected scheduler start has no registered executable turn.
            // Release its code pin exactly once, then retire owner metadata.
            // Neither cleanup stage may claim a successful physical fence
            // until both are complete.
            Throwable pinFailure = releaseUnadmitted(onPhysicalExit, rejected);
            Throwable slotFailure = null;
            if (pinFailure == null) {
                try {
                    synchronized (gate) {
                        if (!tasks.remove(owned))
                            throw new IllegalStateException(
                                    "physical Task owner slot missing at rejection");
                    }
                } catch (RuntimeException | Error failure) {
                    slotFailure = failure;
                    if (failure != rejected) rejected.addSuppressed(failure);
                }
            }
            Throwable fenceFailure = pinFailure != null ? pinFailure : slotFailure;
            if (fenceFailure == null) exitFence.completeFromRuntime(null);
            else exitFence.failFromRuntime(fenceFailure);
            throw rejected;
        }

        // Reference publication cannot allocate, and the owner slot was
        // registered before the scheduler saw the first executable turn.
        // No completion-listener failure can conceal a retirement error.
        shell.attachCompletion(completion);
        if (cancelled) {
            Runnable notification = shell.cancelDeferredNotification();
            if (notification != null) notification.run();
        }
        return shell;
    }

    /** Release one pre-admission image pin without holding a scope monitor. */
    private static Throwable releaseUnadmitted(
            Runnable onPhysicalExit, Throwable rejected) {
        try {
            onPhysicalExit.run();
            return null;
        } catch (RuntimeException | Error failure) {
            if (failure != rejected) rejected.addSuppressed(failure);
            return failure;
        }
    }

    /**
     * Best-effort cleanup for a Task that was provably never admitted.
     * A fault-injected or corrupted owner Set may itself throw during remove.
     * Preserve the original admission error, but never allow that secondary
     * bookkeeping failure to skip release of a previously acquired image pin.
     */
    private void discardUnadmittedSlot(
            OwnershipSlot slot, Throwable admissionFailure) {
        if (slot == null) return;
        try {
            tasks.remove(slot);
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != admissionFailure)
                admissionFailure.addSuppressed(cleanupFailure);
        }
    }

    /** A discarded-handle Task is still owned and always reports failures. */
    public void submitDetached(
            AsyncRuntime.Task<?> work,
            Consumer<? super Throwable> onFailure) {
        Objects.requireNonNull(onFailure, "onFailure");
        OresTask<?> child = submit(work);
        child.getAwaited().whenCompleteRuntime((value, failure) -> {
            if (failure != null) onFailure.accept(failure);
        });
    }

    public OresTaskScope childScope() {
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
            ensureAdmitting();
            OresTaskScope child = new OresTaskScope(runtime, this);
            childScopes.add(child);
            return child;
        }
        }
    }

    public boolean isCancelRequested() {
        synchronized (gate) {
            return cancelled;
        }
    }

    /**
     * Request this scope's cancellation when the external token fires.
     * This is a directed cancellation link, not ownership transfer:
     * cancelling the scope never cancels the upstream source.
     * Host/compiler-only; guest actor links require capability checks.
     */
    public void cancelWhen(OresCancellationToken token) {
        Objects.requireNonNull(token, "token");
        synchronized (gate) {
            ensureAdmitting();
        }
        OresCancellationToken.Registration registration =
                token.onCancellationRequested(this::cancel);
        final boolean detach;
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
                detach = cancelled;
                if (!detach) externalCancellation.add(registration);
            }
        }
        if (detach) registration.close();
    }



    private List<OresTask<?>> snapshotTasksLocked() {
        List<OresTask<?>> owned = new ArrayList<>(tasks.size());
        for (OwnershipSlot slot : tasks) {
            if (slot.task == null)
                throw new IllegalStateException(
                        "TaskScope contains an uncommitted ownership slot");
            owned.add(slot.task);
        }
        return owned;
    }

    /** Package/runtime observation only; not a code-unload authority. */
    boolean physicallyQuiescent() {
        // An observation must linearize with admission throughout the
        // complete tree. Otherwise concurrent child insertion may produce
        // a torn snapshot that incorrectly authorizes code-image unload.
        synchronized (treeAdmissionGate) {
            ArrayDeque<OresTaskScope> pending = new ArrayDeque<>();
            pending.add(this);
            while (!pending.isEmpty()) {
                OresTaskScope scope = pending.removeLast();
                synchronized (scope.gate) {
                    // A failed physical lease release deliberately retains
                    // its slot even after its guest result has settled.
                    if (!scope.tasks.isEmpty()) return false;
                    pending.addAll(scope.childScopes);
                }
            }
            return true;
        }
    }

    int retainedChildScopesForTesting() {
        synchronized (gate) {
            return childScopes.size();
        }
    }

    private void ensureAdmitting() {
        if (cancelled || (runtime != null && runtime.isClosed()))
            throw new RejectedExecutionException(
                    "TaskScope is cancelled or its runtime is closed");
    }

    /** Cancel all owned descendants. Does NOT imply physical quiescence. */
    public boolean cancel() {
        ArrayList<Runnable> notifications = new ArrayList<>();
        // First seal the ENTIRE owned tree, then revoke ALL producers, then
        // invoke any synchronous Future subscribers. A parent cancellation
        // hook must never admit a fresh Task into an as-yet-unvisited child.
        boolean won = false;
        Throwable firstFailure = null;
        try {
            won = cancelTreeIterative(notifications);
        } catch (RuntimeException | Error failure) {
            firstFailure = failure;
        }
        // Even if a cancellation hook/link fails, always deliver deferred
        // notifications already claimed. Withholding one leaves the Future's
        // revocation barrier permanently closed to its host subscribers.
        for (Runnable notification : notifications) {
            try {
                notification.run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure instanceof Error failure) throw failure;
        return won;
    }

    private boolean cancelTreeIterative(ArrayList<Runnable> notifications) {
        // A failed traversal, iterator, set growth or snapshot allocation
        // MUST NOT leave a partially cancelled tree. First build a complete
        // plan while holding the tree's shared admission gate; only after
        // every allocation succeeds may cancellation become observable.
        // The same gate serializes descendant childScope(), submit() and
        // startCompilerFrame(), so no executable Task can escape the plan.
        final ArrayDeque<OresTaskScope> pending = new ArrayDeque<>();
        final List<OresTaskScope> allScopes = new ArrayList<>();
        final List<OresTask<?>> allTasks = new ArrayList<>();
        final List<OresCancellationToken.Registration> allLinks =
                new ArrayList<>();
        final boolean wonRoot;
        synchronized (treeAdmissionGate) {
            pending.add(this);
            while (!pending.isEmpty()) {
                OresTaskScope scope = pending.removeLast();
                synchronized (scope.gate) {
                    // All operations below can allocate/throw; the entire
                    // cancellation tree is still unchanged at this point.
                    allScopes.add(scope);
                    pending.addAll(scope.childScopes);
                    allTasks.addAll(scope.snapshotTasksLocked());
                    allLinks.addAll(scope.externalCancellation);
                }
            }

            // Reserve all notification slots before the irreversible seal.
            // Otherwise an ArrayList growth OOME after a Future cancellation
            // CAS could lose its deferred notification forever.
            notifications.ensureCapacity(allTasks.size());

            // Commit phase is allocation-free and cannot invoke user code.
            // All admissions and descendant creations remain blocked until
            // EVERY scope in the plan has been sealed.
            synchronized (gate) {
                wonRoot = !cancelled;
            }
            for (OresTaskScope scope : allScopes) {
                synchronized (scope.gate) {
                    scope.cancelled = true;
                    scope.externalCancellation.clear();
                }
            }
        }

        // No arbitrary callbacks execute under the admission gate.
        Throwable firstFailure = null;
        // An external registration close may block or throw. Revoke the
        // entire Task tree first, then detach links, then notify listeners.
        for (OresTask<?> task : allTasks) {
            try {
                Runnable notification = task.cancelDeferredNotification();
                if (notification != null) notifications.add(notification);
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        for (OresCancellationToken.Registration link : allLinks) {
            try {
                link.close();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure instanceof Error failure) throw failure;
        return wonRoot;
    }

    /** Runtime-only iterative close frame. Owned scopes form a tree. */
    private static final class CloseFrame {
        final OresTaskScope scope;
        final List<OresTaskScope> children;
        final List<OresTask<?>> owned;
        int nextChild;
        Throwable failure;

        CloseFrame(OresTaskScope scope, List<OresTaskScope> children,
                List<OresTask<?>> owned) {
            this.scope = scope;
            this.children = children;
            this.owned = owned;
        }

        void record(Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
    }

    private CloseFrame beginClose() {
        synchronized (gate) {
            if (joining) return null;
            // Do not commit joining before allocations: an injected OOME in
            // either snapshot must not strand later close() on fullyJoined.
            CloseFrame prepared = new CloseFrame(this,
                    new ArrayList<>(childScopes), snapshotTasksLocked());
            joining = true;
            return prepared;
        }
    }

    /** Cancel and join all descendants before releasing the scope owner. */
    @Override
    public void close() {
        if (ActorRuntime.inActorExecution()
                || AsyncRuntime.isAsyncCarrierThread()
                || OresScheduler.isSchedulerCarrierThread()
                || OresScheduler.current() != null) {
            throw new IllegalStateException(
                    "TaskScope.close cannot block an actor, async carrier or scheduler task turn");
        }

        Throwable cancellationFailure = null;
        try {
            cancel();
        } catch (RuntimeException | Error problem) {
            // cancel() can now fail during its allocation-only preflight,
            // BEFORE it seals any descendant. Such a failure is not a
            // shutdown permit: joining an open tree could return while new
            // child Tasks are admitted. Only continue after the atomic
            // cancellation tree seal was actually committed.
            if (!isCancelRequested()) throw problem;
            // If a producer/subscriber notification failed after sealing,
            // still join every physical frame before reporting that failure.
            cancellationFailure = problem;
        }

        CloseFrame root = beginClose();
        if (root == null) {
            joinExistingClose();
            if (cancellationFailure instanceof RuntimeException failure) throw failure;
            if (cancellationFailure instanceof Error failure) throw failure;
            return;
        }
        if (cancellationFailure != null) root.record(cancellationFailure);

        // Post-order traversal: deeply nested scopes must not consume the
        // Java call stack. Each scope is joined exactly once even when another
        // closing thread has already claimed it.
        ArrayDeque<CloseFrame> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            CloseFrame current = pending.peek();
            if (current.nextChild < current.children.size()) {
                OresTaskScope child = current.children.get(current.nextChild++);
                CloseFrame next = child.beginClose();
                if (next == null) {
                    try {
                        child.joinExistingClose();
                    } catch (RuntimeException | Error problem) {
                        current.record(problem);
                    }
                } else {
                    pending.push(next);
                }
                continue;
            }

            for (OresTask<?> task : current.owned) {
                try {
                    awaitPhysicalExit(task.getDone());
                } catch (RuntimeException | Error problem) {
                    current.record(problem);
                }
            }

            OresTaskScope settled = current.scope;
            settled.closeFailure = current.failure;
            settled.fullyJoined.countDown();
            if (current.failure == null && settled.parent != null) {
                synchronized (settled.parent.gate) {
                    settled.parent.childScopes.remove(settled);
                }
            }
            pending.pop();
            if (current.failure != null && !pending.isEmpty()) {
                pending.peek().record(current.failure);
            }
        }
        if (root.failure instanceof RuntimeException failure) throw failure;
        if (root.failure instanceof Error failure) throw failure;
    }

    /** Wait through host interruption; releasing owned frames is mandatory. */
    private static void awaitPhysicalExit(OresFuture<Void> observed) {
        CountDownLatch exited = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> retirementError =
                new java.util.concurrent.atomic.AtomicReference<>();
        OresFuture.RuntimeWaiterRegistration<Void> registration =
                observed.whenCompleteRuntimeCancellable((value, failure) -> {
                    retirementError.set(failure);
                    exited.countDown();
                });
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    exited.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            registration.detach();
            if (interrupted) Thread.currentThread().interrupt();
        }
        Throwable failure = retirementError.get();
        if (failure != null) throw new IllegalStateException(
                "Task guest turn exited but trusted generation/resource retirement failed",
                failure);
    }

    private void joinExistingClose() {
        // Every successful close() return is a physical-quiescence barrier,
        // including when another host thread already claimed this scope's
        // shutdown. Interruption must not let a concurrent closer escape
        // before the owning closer has joined every child.
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    fullyJoined.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        Throwable failure = closeFailure;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }
}
