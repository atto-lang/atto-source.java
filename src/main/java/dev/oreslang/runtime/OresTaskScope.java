package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
    private final AsyncRuntime runtime;
    private final OresTaskScope parent;
    private final Object gate = new Object();
    // One tree-wide admission and snapshot monitor, shared with descendants.
    // Lock order: treeAdmissionGate -> scope gate.
    private final Object treeAdmissionGate;
    private final Set<OresTask<?>> tasks = new HashSet<>();
    /** Physical lifetime of admitted but not yet handle-bound Tasks. */
    private final Set<OresFuture<Void>> pendingAdmissions = new HashSet<>();
    private final Set<OresTaskScope> childScopes = new HashSet<>();
    /** Directed cancellation observers; do not carry lifecycle ownership. */
    private final List<OresCancellationToken.Registration> externalCancellation =
            new ArrayList<>();
    private final CountDownLatch fullyJoined = new CountDownLatch(1);
    private volatile boolean cancelled;
    private boolean joining;
    private volatile Throwable closeFailure;

    public OresTaskScope(AsyncRuntime runtime) {
        this(runtime, null);
    }

    private OresTaskScope(AsyncRuntime runtime, OresTaskScope parent) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.parent = parent;
        this.treeAdmissionGate = parent == null
                ? new Object() : parent.treeAdmissionGate;
    }

    public <T> OresTask<T> submit(AsyncRuntime.Task<T> work) {
        Objects.requireNonNull(work, "work");
        OresFuture<Void> provisionalExit = new OresFuture<>();
        // Preserve the atomic tree-wide cancellation preflight barrier.
        // Only registration holds these locks, not scheduler dispatch.
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
                ensureAdmitting();
                pendingAdmissions.add(provisionalExit);
            }
        }

        final OresTask<T> child;
        try {
            child = runtime.submitTask(() -> {
                if (cancelled) {
                    throw new CancellationException(
                            "Task scope cancelled before host Task entry");
                }
                return work.run();
            });
        } catch (RuntimeException | Error rejected) {
            provisionalExit.completeFromRuntime(null);
            synchronized (gate) {
                pendingAdmissions.remove(provisionalExit);
            }
            throw rejected;
        }
        final boolean cancelAfterBind;
        synchronized (gate) {
            tasks.add(child);
            pendingAdmissions.remove(provisionalExit);
            cancelAfterBind = cancelled;
        }
        child.getDone().whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                provisionalExit.completeFromRuntime(null);
                synchronized (gate) {
                    tasks.remove(child);
                }
            } else {
                provisionalExit.failFromRuntime(failure);
            }
        });
        if (cancelAfterBind) {
            Runnable notifyCancelled = child.cancelDeferredNotification();
            if (notifyCancelled != null) notifyCancelled.run();
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
        OresFuture<Void> exited = new OresFuture<>();
        try {
            synchronized (treeAdmissionGate) {
                synchronized (gate) {
                    ensureAdmitting();
                    pendingAdmissions.add(exited);
                }
            }
        } catch (RuntimeException | Error rejected) {
            releaseUnadmitted(onPhysicalExit, rejected);
            throw rejected;
        }

        OresFuture<T> completion;
        try {
            // Never call an executor or arbitrary callbacks with either
            // treeAdmissionGate or gate held. A provisional physical join
            // slot prevents concurrent cancellation/close from losing work.
            completion = scheduler.start(resume -> {
                if (cancelled) {
                    throw new CancellationException(
                            "Task scope cancelled before guest Task turn");
                }
                return frame.resume(resume);
            }, () -> {
                try {
                    onPhysicalExit.run();
                    exited.completeFromRuntime(null);
                } catch (RuntimeException | Error failure) {
                    exited.failFromRuntime(failure);
                }
            });
        } catch (RuntimeException | Error rejected) {
            Throwable releaseFailure = releaseUnadmitted(onPhysicalExit, rejected);
            if (releaseFailure == null) {
                exited.completeFromRuntime(null);
                synchronized (gate) {
                    pendingAdmissions.remove(exited);
                }
            } else {
                // A failed generation-pin release is retained as an explicit
                // join failure, even if the caller discards the thrown error.
                exited.failFromRuntime(releaseFailure);
            }
            throw rejected;
        }
        OresTask<T> child = new OresTask<>(completion, exited);
        final boolean cancelAfterBind;
        synchronized (gate) {
            tasks.add(child);
            pendingAdmissions.remove(exited);
            cancelAfterBind = cancelled;
        }
        child.getDone().whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                synchronized (gate) {
                    tasks.remove(child);
                }
            }
        });
        if (cancelAfterBind) {
            Runnable notifyCancelled = child.cancelDeferredNotification();
            if (notifyCancelled != null) notifyCancelled.run();
        }
        return child;
    }

    private static Throwable releaseUnadmitted(
            Runnable onPhysicalExit, Throwable rejected) {
        try {
            onPhysicalExit.run();
            return null;
        } catch (RuntimeException | Error releaseFailure) {
            if (releaseFailure != rejected) {
                rejected.addSuppressed(releaseFailure);
            }
            return releaseFailure;
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
        final boolean unregister;
        synchronized (treeAdmissionGate) {
            synchronized (gate) {
                unregister = cancelled;
                if (!unregister) externalCancellation.add(registration);
            }
        }
        // Host detach must not execute under either admission lock.
        if (unregister) registration.close();
    }

    private void ensureAdmitting() {
        if (cancelled || runtime.isClosed())
            throw new RejectedExecutionException("TaskScope is cancelled or its runtime is closed");
    }

    /** Cancel all owned descendants. Does NOT imply physical quiescence. */
    public boolean cancel() {
        ArrayList<Runnable> notifications = new ArrayList<>();
        // Seal the ENTIRE descendant tree before any Task revocation hook can
        // admit new work to a child that has not yet been visited.
        boolean won = false;
        Throwable firstFailure = null;
        try {
            won = cancelTreeIterative(notifications);
        } catch (RuntimeException | Error failure) {
            firstFailure = failure;
        }
        // A faulty producer hook cannot strand already-claimed deferred
        // notifications behind a permanently closed Future revocation gate.
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
        // Every snapshot/allocation must succeed before ANY admission seal.
        // Shared treeAdmissionGate excludes descendant creation and Task
        // submission while the plan is prepared and then committed.
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
                    allScopes.add(scope);
                    pending.addAll(scope.childScopes);
                    allTasks.addAll(scope.tasks);
                    allLinks.addAll(scope.externalCancellation);
                }
            }

            // A failed ArrayList capacity reserve cannot lose a notification
            // after a Future cancellation CAS. Do all resizing precommit.
            notifications.ensureCapacity(allTasks.size());
            synchronized (gate) {
                wonRoot = !cancelled;
            }
            // No further allocation or callback before every scope is sealed.
            for (OresTaskScope scope : allScopes) {
                synchronized (scope.gate) {
                    scope.cancelled = true;
                    scope.externalCancellation.clear();
                }
            }
        }

        Throwable firstFailure = null;
        // Revocation of every producer takes priority over detaching
        // external observers: host detach is permitted to block or throw.
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
        final List<OresFuture<Void>> pending;
        int nextChild;
        Throwable failure;

        CloseFrame(OresTaskScope scope, List<OresTaskScope> children,
                List<OresTask<?>> owned, List<OresFuture<Void>> pending) {
            this.scope = scope;
            this.children = children;
            this.owned = owned;
            this.pending = pending;
        }

        void record(Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
    }

    private CloseFrame beginClose() {
        synchronized (gate) {
            if (joining) return null;
            // Failed snapshot allocation must not strand subsequent closers
            // on a join latch that no owning closer can ever count down.
            CloseFrame prepared = new CloseFrame(this,
                    new ArrayList<>(childScopes), new ArrayList<>(tasks),
                    new ArrayList<>(pendingAdmissions));
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
            // The snapshot may capture admission before a Task handle exists.
            // That reservation must not disappear before physical retirement.
            for (OresFuture<Void> pending : current.pending) {
                try {
                    awaitPhysicalExit(pending);
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
