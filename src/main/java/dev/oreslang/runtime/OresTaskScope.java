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
        /**
         * Compiler-frame physical retirement authority. Null for ordinary
         * AsyncRuntime Tasks whose own quiescence fence is authoritative.
         */
        private PhysicalRetirementOwner physicalRetirement;
    }

    /**
     * Retains the exact trusted retirement callback after the scheduler's
     * guest carrier has physically unwound. A callback failure is NOT turned
     * into a terminal Future: the scope keeps this owner and a later host
     * close attempt retries the same release before reporting quiescence.
     */
    private static final class PhysicalRetirementOwner {
        private final Runnable retirement;
        private final OresFuture<Void> retired = new OresFuture<>();
        private final CountDownLatch physicalExitObserved = new CountDownLatch(1);
        private boolean exitObserved;
        /** Trusted retirement callback returned normally. */
        private boolean releaseComplete;
        /**
         * The non-cancellable physical retirement Future has been published.
         * Keep this separate from releaseComplete: publication itself is a
         * retryable stage and must not rerun an already-successful external
         * release callback.
         */
        private boolean retirementPublished;
        // Package/reflection fault injection after release, before publication.
        private Runnable afterReleaseBeforePublishFaultForTesting;

        PhysicalRetirementOwner(Runnable retirement) {
            this.retirement = Objects.requireNonNull(retirement, "retirement");
        }

        OresFuture<Void> retiredFence() {
            return retired;
        }

        void onPhysicalExit() {
            synchronized (this) {
                if (!exitObserved) {
                    exitObserved = true;
                    physicalExitObserved.countDown();
                }
            }
            // Scheduler retirement must not throw back through the carrier
            // callback. A failed release remains owned for host reconciliation.
            try {
                retryRetirementAfterExit();
            } catch (RuntimeException | Error ignored) {
                // Exact callback + ownership slot remain live.
            }
        }

        void awaitExitAndRetire() {
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        physicalExitObserved.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            retryRetirementAfterExit();
        }

        private synchronized void retryRetirementAfterExit() {
            if (retirementPublished) return;
            if (!exitObserved) {
                throw new IllegalStateException(
                        "trusted Task retirement attempted before physical carrier exit");
            }

            if (!releaseComplete) {
                retirement.run();
                // Once the external release callback returned normally, never
                // replay it merely because Future publication later failed.
                releaseComplete = true;
            }

            Runnable injected = afterReleaseBeforePublishFaultForTesting;
            if (injected != null) injected.run();

            // Publish while holding the same owner lock. A concurrent close
            // cannot observe retirementPublished before the Future terminal
            // state is visible. If publication throws, the exact owner remains
            // retryable without duplicating the successful external release.
            if (!retired.isDone()) {
                if (!retired.completeFromRuntime(null) && !retired.isDone()) {
                    throw new IllegalStateException(
                            "trusted Task retirement fence rejected publication");
                }
            }
            retirementPublished = true;
        }
    }

    // Deliberately non-final only for package/reflection fault-injection tests.
    // Production never replaces this set.
    private Set<OwnershipSlot> tasks = new HashSet<>();
    private final Set<OresTaskScope> childScopes = new HashSet<>();
    /** Directed cancellation observers; do not carry lifecycle ownership. */
    private final List<OresCancellationToken.Registration> externalCancellation =
            new ArrayList<>();
    private boolean cancelled;
    /** True only while one host owns the current close attempt. */
    private boolean joining;
    /** Published only after a close attempt joins every owned descendant. */
    private boolean closeComplete;
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
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
            ensureAdmitting();
            if (runtime == null)
                throw new RejectedExecutionException(
                        "compiler-owned TaskScope has no ordinary AsyncRuntime admission");

            OwnershipSlot slot = new OwnershipSlot();
            // Any allocation/growth failure occurs before runtime submission.
            if (!tasks.add(slot))
                throw new IllegalStateException("duplicate Task ownership slot");

            final OresTask<T> child;
            try {
                child = runtime.submitTask(work);
                slot.task = child; // allocation-free publication into owner
            } catch (RuntimeException | Error admissionFailure) {
                discardUnadmittedSlot(slot, admissionFailure);
                throw admissionFailure;
            }

            try {
                OwnershipSlot owned = slot;
                child.quiescenceFence().whenCompleteRuntime((value, failure) -> {
                    if (failure == null) {
                        synchronized (gate) {
                            tasks.remove(owned);
                        }
                    }
                });
            } catch (RuntimeException | Error observerFailure) {
                // The Task is already admitted and remains in its pre-reserved
                // slot. Seal this scope and request cancellation; close() can
                // still join the physical fence even without the optimization
                // callback that normally prunes the slot.
                cancelled = true;
                try {
                    child.cancel();
                } catch (RuntimeException | Error cancelFailure) {
                    if (cancelFailure != observerFailure)
                        observerFailure.addSuppressed(cancelFailure);
                }
                throw observerFailure;
            }
            return child;
        }
        }
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
     * leases. The onPhysicalExit callback is first invoked AFTER the final
     * guest carrier turn has unwound, not when the cancelable result settles.
     * If it throws, the exact callback remains owned and may be retried by a
     * later host close. If callback release succeeds but fence publication
     * fails, only publication is retried; the successful external release is
     * never replayed. Successful retirement is published exactly once.
     * A suspends call uses its parent's existing Task and does not re-enter
     * this admission path. A child task needs its own independent code pin.
     *
     * The caller must acquire its pin BEFORE admission and must preserve it
     * on all preparation, cancellation and close paths. The callback must
     * be nonblocking, host-only, retry-safe/idempotent on failure, and must
     * not execute guest code. The code-image
     * owner must independently check its active pin counts before unload.
     */
    <T> OresTask<T> startCompilerFrame(
            OresScheduler scheduler, OresScheduler.Task<T> frame,
            Runnable onPhysicalExit) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(onPhysicalExit, "onPhysicalExit");
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
            try {
                ensureAdmitting();
            } catch (RuntimeException | Error rejectedByScope) {
                try {
                    onPhysicalExit.run();
                } catch (RuntimeException | Error releaseFailure) {
                    if (releaseFailure != rejectedByScope)
                        rejectedByScope.addSuppressed(releaseFailure);
                }
                throw rejectedByScope;
            }

            OwnershipSlot slot = null;
            PhysicalRetirementOwner retirementOwner = null;
            OresTask<T> child = null;
            try {
                // Every ownership object and callback registration is created
                // before scheduler admission. In particular HashSet growth
                // cannot strand an admitted actor/code-generation pin.
                slot = new OwnershipSlot();
                retirementOwner = new PhysicalRetirementOwner(onPhysicalExit);
                child = new OresTask<>(retirementOwner.retiredFence());
                slot.task = child;
                slot.physicalRetirement = retirementOwner;
                if (!tasks.add(slot))
                    throw new IllegalStateException("duplicate Task ownership slot");
                OwnershipSlot owned = slot;
                retirementOwner.retiredFence().whenCompleteRuntime((ignored, failure) -> {
                    if (failure == null) {
                        synchronized (gate) {
                            tasks.remove(owned);
                        }
                    }
                });
            } catch (RuntimeException | Error preparationFailure) {
                discardUnadmittedSlot(slot, preparationFailure);
                try {
                    onPhysicalExit.run();
                } catch (RuntimeException | Error releaseFailure) {
                    if (releaseFailure != preparationFailure)
                        preparationFailure.addSuppressed(releaseFailure);
                }
                throw preparationFailure;
            }

            final OwnershipSlot admittedSlot = slot;
            final PhysicalRetirementOwner admittedRetirement = retirementOwner;
            final OresTask<T> ownedChild = child;
            final OresFuture<T> completion;
            try {
                completion = scheduler.start(frame, admittedRetirement::onPhysicalExit);
            } catch (RuntimeException | Error rejectedBeforeAdmission) {
                discardUnadmittedSlot(admittedSlot, rejectedBeforeAdmission);
                try {
                    onPhysicalExit.run();
                } catch (RuntimeException | Error releaseFailure) {
                    if (releaseFailure != rejectedBeforeAdmission)
                        rejectedBeforeAdmission.addSuppressed(releaseFailure);
                }
                throw rejectedBeforeAdmission;
            }

            // Preallocated handle publication is an allocation-free assignment.
            ownedChild.attachCompletion(completion);
            return ownedChild;
        }
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
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
                ensureAdmitting();
            }
        }
        // Registration can invoke the callback inline for an already
        // cancelled source. Never call arbitrary token plumbing while holding
        // either TaskScope admission monitor.
        OresCancellationToken.Registration registration =
                token.onCancellationRequested(this::cancel);
        boolean owned = false;
        try {
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    if (!cancelled && (runtime == null || !runtime.isClosed())) {
                        // ArrayList growth is fallible. Claim ownership only
                        // AFTER insertion returns; on failure detach the
                        // independently registered upstream listener.
                        externalCancellation.add(registration);
                        owned = true;
                    }
                }
            }
        } catch (RuntimeException | Error admissionFailure) {
            try {
                registration.close();
            } catch (RuntimeException | Error detachFailure) {
                if (detachFailure != admissionFailure)
                    admissionFailure.addSuppressed(detachFailure);
            }
            throw admissionFailure;
        }
        // A concurrent scope cancellation may have won between preflight
        // and publication. A no-longer-owned listener must not retain this
        // scope via the upstream token; detach outside all scope monitors.
        if (!owned) registration.close();
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

    private List<OwnershipSlot> snapshotOwnershipSlotsLocked() {
        List<OwnershipSlot> owned = new ArrayList<>(tasks.size());
        for (OwnershipSlot slot : tasks) {
            if (slot.task == null)
                throw new IllegalStateException(
                        "TaskScope contains an uncommitted ownership slot");
            owned.add(slot);
        }
        return owned;
    }

    /**
     * One coherent, host-only observation of this subtree's physical owner
     * slots. Each Task start and child-scope creation takes the shared tree
     * admission gate BEFORE a per-scope gate. Hold that same gate throughout
     * traversal: otherwise a new Task or descendant could be admitted after
     * its parent was inspected, and an in-progress observer could incorrectly
     * return true. This is still NOT code-unload authority: a successful
     * observation by itself neither seals future admission nor authenticates
     * the generation's external FFI/shared-memory pins.
     */
    boolean physicallyQuiescent() {
        synchronized (treeAdmissionGate) {
            ArrayDeque<OresTaskScope> pending = new ArrayDeque<>();
            pending.add(this);
            while (!pending.isEmpty()) {
                OresTaskScope scope = pending.removeLast();
                synchronized (scope.gate) {
                    // Failed trusted physical retirement retains its slot.
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
        for (OresCancellationToken.Registration link : allLinks) {
            try {
                link.close();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        for (OresTask<?> task : allTasks) {
            try {
                Runnable notification = task.cancelDeferredNotification();
                if (notification != null) notifications.add(notification);
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
        final List<OwnershipSlot> owned;
        int nextChild;
        Throwable failure;

        CloseFrame(OresTaskScope scope, List<OresTaskScope> children,
                List<OwnershipSlot> owned) {
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
            if (closeComplete || joining) return null;
            // Do not commit joining before allocations: an injected OOME in
            // either snapshot must not strand later close() behind a phantom
            // owner. A failed close attempt releases this claim for retry.
            CloseFrame prepared = new CloseFrame(this,
                    new ArrayList<>(childScopes), snapshotOwnershipSlotsLocked());
            closeFailure = null;
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

            for (OwnershipSlot slot : current.owned) {
                try {
                    if (slot.physicalRetirement != null) {
                        // Wait for the carrier to be physically gone, then
                        // retry the exact trusted generation/resource release.
                        slot.physicalRetirement.awaitExitAndRetire();
                    } else {
                        awaitPhysicalExit(slot.task.getDone());
                    }
                } catch (RuntimeException | Error problem) {
                    current.record(problem);
                }
            }

            OresTaskScope settled = current.scope;
            synchronized (settled.gate) {
                settled.closeFailure = current.failure;
                settled.joining = false;
                if (current.failure == null) settled.closeComplete = true;
                settled.gate.notifyAll();
            }
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
        // Wait only for the CURRENT close attempt. A failed attempt releases
        // joining so a later host call can retry retained physical retirement
        // owners; a one-shot latch would permanently replay the first failure.
        boolean interrupted = false;
        Throwable failure;
        synchronized (gate) {
            while (joining) {
                try {
                    gate.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            failure = closeFailure;
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }
}
