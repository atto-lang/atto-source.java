package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
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

    /**
     * Runtime-minted identity witness for one exact compiler TaskScope epoch.
     * Its constructor is private: compiler code may compare/retain the witness
     * it receives from this runtime, but cannot manufacture an attestation.
     */
    public static final class CompilerScopeEpoch {
        private CompilerScopeEpoch() { }
    }

    private final CompilerScopeEpoch compilerScopeEpoch =
            new CompilerScopeEpoch();

    /**
     * Compiler-issued memory-loan lifecycle. Implementations are expected to
     * be opaque capabilities: ordinary guest/host code cannot manufacture the
     * verifier ticket they retire or the runtime epoch that attests lifecycle.
     */
    public interface CompilerLoanRetirement {
        void bindScope(CompilerScopeEpoch scopeEpoch);
        void bindChild(CompilerScopeEpoch scopeEpoch, OresTask<?> child);
        void retireNeverStarted(CompilerScopeEpoch scopeEpoch);
        void retireAfterPhysicalQuiescence(
                CompilerScopeEpoch scopeEpoch, OresTask<?> child);
    }

    private static final CompilerLoanRetirement NO_COMPILER_LOAN =
            new CompilerLoanRetirement() {
                @Override public void bindScope(CompilerScopeEpoch scopeEpoch) { }
                @Override public void bindChild(
                        CompilerScopeEpoch scopeEpoch, OresTask<?> child) { }
                @Override public void retireNeverStarted(
                        CompilerScopeEpoch scopeEpoch) { }
                @Override public void retireAfterPhysicalQuiescence(
                        CompilerScopeEpoch scopeEpoch, OresTask<?> child) { }
            };

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
        // Protected by the owning scope gate. A legitimate rejection before
        // slot insertion must not report a fake cleanup failure.
        private boolean registered;
    }

    // Deliberately non-final only for package/reflection fault-injection tests.
    // Production never replaces this set.
    private Set<OwnershipSlot> tasks = new HashSet<>();
    private final Set<OresTaskScope> childScopes = new HashSet<>();
    /** Directed cancellation observers; do not carry lifecycle ownership. */
    private final List<OresCancellationToken.Registration> externalCancellation =
            new ArrayList<>();
    private final CountDownLatch fullyJoined = new CountDownLatch(1);
    private boolean cancelled;
    private boolean joining;
    private volatile Throwable closeFailure;
    /**
     * Exact trusted authority identities whose release could not be proven.
     * This covers both compiler-loan capabilities and physical code/resource
     * cleanup callbacks. An empty Task slot set is not quiescence while this
     * ledger is non-empty.
     */
    private final IdentityHashMap<Object, Throwable> failedCompilerAuthorities =
            new IdentityHashMap<>();
    private volatile Throwable compilerAuthorityFailure;

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

        // Reserve the physical owner and install its lifetime observer before
        // allowing executor dispatch. Every child is present in cancellation
        // and close snapshots even when the executor is blocked on enqueue.
        AsyncRuntime.TaskAdmission<T> admission = runtime.prepareTaskAdmission();
        OwnershipSlot slot = new OwnershipSlot();
        slot.task = admission.owner;
        // Only an inserted slot is eligible for later retirement.
        // For adversarial insert-then-throw collections, contains() detects
        // the partial registration even if registered was never set.
        // Hook registration happens before the owner becomes executable.
        // Generic Future listeners intentionally isolate thrown callbacks,
        // so slot retirement must run as part of TaskExit itself.
        admission.installRetirement(() -> {
            synchronized (gate) {
                if (!slot.registered && !tasks.contains(slot)) return;
                if (!tasks.remove(slot))
                    throw new IllegalStateException(
                            "ordinary Task owner slot missing at physical retirement");
            }
        });

        try {
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    ensureAdmitting();
                    if (!tasks.add(slot))
                        throw new IllegalStateException("duplicate Task ownership slot");
                    slot.registered = true;
                }
            }
        } catch (RuntimeException | Error reservationFailure) {
            // Even an insert-then-throw owner collection cannot strand an
            // unbound Task. The abort fires its trusted retirement callback
            // outside scope locks and settles the physical-exit fence.
            try {
                runtime.abandonPreparedTask(admission);
            } catch (RuntimeException | Error rollbackFailure) {
                if (rollbackFailure != reservationFailure)
                    reservationFailure.addSuppressed(rollbackFailure);
            }
            throw reservationFailure;
        }

        // Never execute executor callbacks while holding either scope
        // monitor. A cancelled provisional child retains its ownership
        // witness until its real carrier exits or admission is rejected.
        runtime.submitPreparedTask(admission, () -> {
            if (isCancelRequested()) {
                throw new java.util.concurrent.CancellationException(
                        "Task scope cancelled before async guest entry");
            }
            return work.run();
        });
        return admission.owner;
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
        return startCompilerFrame(
                scheduler, frame, () -> { }, NO_COMPILER_LOAN);
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
        return startCompilerFrame(
                scheduler, frame, onPhysicalExit, NO_COMPILER_LOAN);
    }

    /**
     * Compiler-only scoped-memory admission. The loan capability binds first
     * to this exact scope epoch and then to the exact provisional child owner.
     * Scheduler rejection and physical exit are different retirement edges.
     */
    <T> OresTask<T> startCompilerFrame(
            OresScheduler scheduler, OresScheduler.Task<T> frame,
            Runnable onPhysicalExit,
            CompilerLoanRetirement loanRetirement) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(onPhysicalExit, "onPhysicalExit");
        Objects.requireNonNull(loanRetirement, "loanRetirement");
        try {
            loanRetirement.bindScope(compilerScopeEpoch);
        } catch (RuntimeException | Error bindingFailure) {
            // A trusted lifecycle object may fail after partially accepting
            // this exact runtime epoch. Retain its identity even though no
            // provisional Task owner exists yet.
            poisonCompilerLoan(loanRetirement, bindingFailure);
            throw bindingFailure;
        }

        // Reserve the owner before executable dispatch, but never run a
        // scheduler, guest turn or code-image release under admission locks.
        OresFuture<Void> pendingFence = null;
        OresTask<T> pendingOwner = null;
        OwnershipSlot pendingSlot = null;
        try {
            pendingFence = new OresFuture<>();
            pendingOwner = OresTask.provisional(pendingFence);
            loanRetirement.bindChild(compilerScopeEpoch, pendingOwner);
            pendingSlot = new OwnershipSlot();
            pendingSlot.task = pendingOwner;
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    ensureAdmitting();
                    if (!tasks.add(pendingSlot))
                        throw new IllegalStateException("duplicate Task ownership slot");
                    pendingSlot.registered = true;
                }
            }
        } catch (RuntimeException | Error preparationFailure) {
            // External/code cleanup may still use borrowed memory, so it must
            // retire before the compiler loan. Only after BOTH trusted
            // authorities retire may a provisional owner slot disappear.
            Throwable releaseFailure = null;
            try {
                onPhysicalExit.run();
            } catch (RuntimeException | Error failure) {
                releaseFailure = failure;
                poisonPhysicalExitAndDependentLoan(
                        onPhysicalExit, loanRetirement, failure);
                if (failure != preparationFailure)
                    preparationFailure.addSuppressed(failure);
            }
            if (releaseFailure == null) {
                try {
                    loanRetirement.retireNeverStarted(compilerScopeEpoch);
                } catch (RuntimeException | Error failure) {
                    releaseFailure = failure;
                    poisonCompilerLoan(loanRetirement, failure);
                    if (failure != preparationFailure)
                        preparationFailure.addSuppressed(failure);
                }
            }

            Throwable bookkeepingFailure = null;
            if (releaseFailure == null && pendingSlot != null) {
                try {
                    synchronized (gate) {
                        // A sealed scope can reject before insertion. Do NOT
                        // invoke a hostile removal hook for a never-owned slot.
                        // An insert-then-throw collection is still detected
                        // by contains() when registered is false.
                        if (pendingSlot.registered || tasks.contains(pendingSlot)) {
                            if (!tasks.remove(pendingSlot))
                                throw new IllegalStateException(
                                        "provisional Task owner rollback did not remove reserved slot");
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    bookkeepingFailure = failure;
                    if (failure != preparationFailure)
                        preparationFailure.addSuppressed(failure);
                }
            }
            if (pendingFence != null) {
                Throwable fenceFailure = releaseFailure != null
                        ? releaseFailure : bookkeepingFailure;
                if (fenceFailure == null) pendingFence.completeFromRuntime(null);
                else pendingFence.failFromRuntime(fenceFailure);
            }
            throw preparationFailure;
        }

        final OwnershipSlot ownedSlot = pendingSlot;
        final OresFuture<Void> exitFence = pendingFence;
        final OresTask<T> ownedChild = pendingOwner;
        final OresFuture<T> completion;
        try {
            completion = scheduler.start(resume -> {
                // Cancellation between reservation and Future binding seals
                // admission to new guest turns, not the physical carrier.
                if (isCancelRequested()) {
                    throw new java.util.concurrent.CancellationException(
                            "Task scope cancelled before guest turn");
                }
                return frame.resume(resume);
            }, () -> {
                // Retire the code pin once, AFTER the final carrier turn.
                // Do not discard a failed pin. Prune owner bookkeeping
                // before completing the quiescence Future, because generic
                // Future observers swallow callback exceptions.
                // The memory loan is the LAST external authority returned
                // after guest exit. Code/FFI/resource cleanup may still rely on
                // the borrowed allocation, and a failed cleanup must preserve
                // both the loan and the owner slot as reclamation pins.
                try {
                    onPhysicalExit.run();
                } catch (RuntimeException | Error releaseFailure) {
                    poisonPhysicalExitAndDependentLoan(
                            onPhysicalExit, loanRetirement, releaseFailure);
                    exitFence.failFromRuntime(releaseFailure);
                    return;
                }
                try {
                    loanRetirement.retireAfterPhysicalQuiescence(
                            compilerScopeEpoch, ownedChild);
                } catch (RuntimeException | Error loanFailure) {
                    poisonCompilerLoan(loanRetirement, loanFailure);
                    exitFence.failFromRuntime(loanFailure);
                    return;
                }
                try {
                    synchronized (gate) {
                        if (!tasks.remove(ownedSlot))
                            throw new IllegalStateException(
                                    "physical Task owner slot missing at retirement");
                    }
                    exitFence.completeFromRuntime(null);
                } catch (RuntimeException | Error bookkeepingFailure) {
                    exitFence.failFromRuntime(bookkeepingFailure);
                }
            });
        } catch (RuntimeException | Error rejectedBeforeDispatch) {
            // Scheduler.start threw before TaskRunner registration. Preserve
            // the same dependency ordering as physical exit: code/FFI/resource
            // cleanup first, then memory-loan return, then owner-slot removal.
            Throwable releaseFailure = null;
            try {
                onPhysicalExit.run();
            } catch (RuntimeException | Error failure) {
                releaseFailure = failure;
                poisonPhysicalExitAndDependentLoan(
                        onPhysicalExit, loanRetirement, failure);
                if (failure != rejectedBeforeDispatch)
                    rejectedBeforeDispatch.addSuppressed(failure);
            }
            if (releaseFailure == null) {
                try {
                    loanRetirement.retireNeverStarted(compilerScopeEpoch);
                } catch (RuntimeException | Error failure) {
                    releaseFailure = failure;
                    poisonCompilerLoan(loanRetirement, failure);
                    if (failure != rejectedBeforeDispatch)
                        rejectedBeforeDispatch.addSuppressed(failure);
                }
            }
            Throwable bookkeepingFailure = null;
            if (releaseFailure == null) {
                try {
                    synchronized (gate) {
                        if (!tasks.remove(ownedSlot))
                            throw new IllegalStateException(
                                    "physical Task owner slot missing at retirement");
                    }
                } catch (RuntimeException | Error failure) {
                    bookkeepingFailure = failure;
                    if (failure != rejectedBeforeDispatch)
                        rejectedBeforeDispatch.addSuppressed(failure);
                }
            }
            Throwable fenceFailure = releaseFailure != null
                    ? releaseFailure : bookkeepingFailure;
            if (fenceFailure == null) exitFence.completeFromRuntime(null);
            else exitFence.failFromRuntime(fenceFailure);
            throw rejectedBeforeDispatch;
        }

        // Cancellation may have sealed this scope while the scheduler was
        // admitting the Task. The owner replays it without scope locks.
        ownedChild.attachCompletion(completion);
        return ownedChild;
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
        // Host unregister may block or invoke callbacks: never execute it
        // under the tree admission monitor or this scope's ownership lock.
        if (detach) registration.close();
    }



    private void poisonCompilerAuthority(Object authority, Throwable failure) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(failure, "failure");
        synchronized (gate) {
            failedCompilerAuthorities.put(authority, failure);
            if (compilerAuthorityFailure == null) {
                compilerAuthorityFailure = failure;
            } else if (compilerAuthorityFailure != failure) {
                compilerAuthorityFailure.addSuppressed(failure);
            }
        }
    }

    private void poisonCompilerLoan(
            CompilerLoanRetirement loanRetirement, Throwable failure) {
        if (loanRetirement != NO_COMPILER_LOAN) {
            poisonCompilerAuthority(loanRetirement, failure);
        }
    }

    private void poisonPhysicalExitAndDependentLoan(
            Runnable onPhysicalExit,
            CompilerLoanRetirement loanRetirement,
            Throwable failure) {
        poisonCompilerAuthority(onPhysicalExit, failure);
        // Cleanup failed before memory authority could be returned. Retain the
        // exact loan capability too, because its verifier ticket is still live.
        poisonCompilerLoan(loanRetirement, failure);
    }

    Throwable compilerAuthorityFailureForTesting() {
        return compilerAuthorityFailure;
    }

    int failedCompilerAuthoritiesForTesting() {
        synchronized (gate) {
            return failedCompilerAuthorities.size();
        }
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
                    // Failed trusted physical retirement retains either its
                    // owner slot or an exact poisoned authority identity.
                    if (!scope.tasks.isEmpty()
                            || !scope.failedCompilerAuthorities.isEmpty()) {
                        return false;
                    }
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

        // Revoke every Task producer before executing any potentially
        // blocking or throwing external token-unregister callback. The
        // tree was already atomically sealed and all Future subscriber
        // notifications remain deferred until the caller drains them.
        Throwable firstFailure = null;
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
            if (!failedCompilerAuthorities.isEmpty()) {
                prepared.record(new IllegalStateException(
                        "compiler Task authority retirement failed; scope remains non-quiescent",
                        compilerAuthorityFailure));
            }
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
