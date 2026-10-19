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
    private final Set<OresTask<?>> tasks = new HashSet<>();
    /** Unbound Task join slots, never discarded before physical exit. */
    private final Set<OresFuture<Void>> pendingCompilerFrames = new HashSet<>();
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
    }

    public <T> OresTask<T> submit(AsyncRuntime.Task<T> work) {
        Objects.requireNonNull(work, "work");
        // Host async executor may run callbacks inline before submitTask
        // returns. Track an independent physical join slot while it is
        // unbound, just as for a compiler-generated scheduler frame.
        OresFuture<Void> admissionExit = new OresFuture<>();
        synchronized (gate) {
            ensureAdmitting();
            pendingCompilerFrames.add(admissionExit);
        }

        final OresTask<T> child;
        try {
            child = runtime.submitTask(() -> {
                // Cancellation may win after admission reservation but before
                // the compatibility worker starts running guest code.
                if (cancelled) {
                    throw new CancellationException(
                            "Task scope cancelled before host Task entry");
                }
                return work.run();
            });
        } catch (RuntimeException | Error rejected) {
            // AsyncRuntime.submitTask already closes its own failed
            // registration. No work was admitted by this scope.
            admissionExit.completeFromRuntime(null);
            synchronized (gate) {
                pendingCompilerFrames.remove(admissionExit);
            }
            throw rejected;
        }

        final boolean cancelAfterBind;
        synchronized (gate) {
            tasks.add(child);
            pendingCompilerFrames.remove(admissionExit);
            cancelAfterBind = cancelled;
        }
        // Complete the former pending slot only after true physical exit,
        // even if close() snapped it before the Task handle was bound.
        child.getDone().whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                admissionExit.completeFromRuntime(null);
                synchronized (gate) {
                    tasks.remove(child);
                }
            } else {
                admissionExit.failFromRuntime(failure);
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

        // Reserve a PHYSICAL lifetime slot before exposing the Task to a
        // scheduler, which may execute inline or invoke callbacks synchronously.
        // The slot survives cancellation, result settlement and close() until
        // the scheduler-confirmed exit hook and trusted image release complete.
        OresFuture<Void> exited = new OresFuture<>();
        try {
            synchronized (gate) {
                ensureAdmitting();
                pendingCompilerFrames.add(exited);
            }
        } catch (RuntimeException | Error rejectedByScope) {
            // Nothing was scheduled. Release the caller's already-acquired
            // code pin outside the scope monitor, exactly once.
            releaseUnadmitted(onPhysicalExit, rejectedByScope);
            throw rejectedByScope;
        }

        OresFuture<T> completion;
        try {
            // No scope monitor may be held across enqueue, an inline worker
            // dispatch, source execution, or arbitrary Future callbacks.
            completion = scheduler.start(resume -> {
                // Check the scope's volatile cancellation decision at every
                // continuation entry. Admission reserves lifetime, NOT a
                // right to begin guest work after parent cancellation.
                // In-flight turns still unwind at their own safe points.
                if (cancelled) {
                    throw new CancellationException(
                            "Task scope cancelled before guest Task turn");
                }
                return frame.resume(resume);
            }, () -> {
                try {
                    onPhysicalExit.run();
                    exited.completeFromRuntime(null);
                } catch (RuntimeException | Error releaseFailure) {
                    exited.failFromRuntime(releaseFailure);
                }
            });
        } catch (RuntimeException | Error rejectedBeforeAdmission) {
            // Scheduler.start throws only before TaskRunner registration.
            // Any post-registration enqueue failure instead completes via
            // the scheduler's physical-exit callback.
            Throwable releaseFailure = releaseUnadmitted(
                    onPhysicalExit, rejectedBeforeAdmission);
            if (releaseFailure == null) {
                exited.completeFromRuntime(null);
                synchronized (gate) {
                    pendingCompilerFrames.remove(exited);
                }
            } else {
                // Keep the failed physical lease fence visible to close().
                exited.failFromRuntime(releaseFailure);
            }
            throw rejectedBeforeAdmission;
        }

        OresTask<T> child = new OresTask<>(completion, exited);
        final boolean cancelAfterBind;
        synchronized (gate) {
            // Transfer a reserved join slot into the live Task set atomically
            // with close() snapshots. Cancellation may have already sealed
            // admission; it cannot discard the reservation or code lease.
            tasks.add(child);
            pendingCompilerFrames.remove(exited);
            cancelAfterBind = cancelled;
        }
        // Future completion callbacks may run inline. Never subscribe while
        // holding gate or silently discard failed code-generation retirement.
        child.getDone().whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) {
                synchronized (gate) {
                    tasks.remove(child);
                }
            }
        });
        if (cancelAfterBind) {
            // Cancellation is logical: do not interrupt/release the pthread
            // or claim quiescence merely because the Future is cancelled.
            Runnable notifyCancelled = child.cancelDeferredNotification();
            if (notifyCancelled != null) notifyCancelled.run();
        }
        return child;
    }

    /** Release one unadmitted pin, preserving both original and cleanup error. */
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
        synchronized (gate) {
            ensureAdmitting();
            OresTaskScope child = new OresTaskScope(runtime, this);
            childScopes.add(child);
            return child;
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
        synchronized (gate) {
            unregister = cancelled;
            if (!unregister) externalCancellation.add(registration);
        }
        // The subscription's close() may invoke arbitrary host code and
        // must not run while holding the owning scope's admission monitor.
        if (unregister) registration.close();
    }



    private void ensureAdmitting() {
        if (cancelled || runtime.isClosed())
            throw new RejectedExecutionException("TaskScope is cancelled or its runtime is closed");
    }

    /** Cancel all owned descendants. Does NOT imply physical quiescence. */
    public boolean cancel() {
        List<Runnable> notifications = new ArrayList<>();
        List<OresCancellationToken.Registration> toDetach = new ArrayList<>();
        // Revoke the entire subtree before running ANY synchronous host
        // detach or notification callback. An uncooperative observer must not
        // leave a sibling Task or descendant with unrevoked authority.
        boolean wonRoot = cancelTreeIterative(notifications, toDetach);
        Throwable firstFailure = null;
        for (OresCancellationToken.Registration registration : toDetach) {
            try {
                registration.close();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
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
        return wonRoot;
    }

    private boolean cancelTreeIterative(
            List<Runnable> notifications,
            List<OresCancellationToken.Registration> toDetach) {
        ArrayDeque<OresTaskScope> pending = new ArrayDeque<>();
        pending.add(this);
        boolean wonRoot = false;
        while (!pending.isEmpty()) {
            OresTaskScope scope = pending.removeLast();
            final List<OresTaskScope> children;
            final List<OresTask<?>> owned;
            final List<OresCancellationToken.Registration> inbound;
            synchronized (scope.gate) {
                // Concurrent cancel may already have claimed this scope.
                // Traverse grandchildren regardless of the prior claim.
                boolean firstClaim = !scope.cancelled;
                scope.cancelled = true;
                if (scope == this && firstClaim) wonRoot = true;
                children = new ArrayList<>(scope.childScopes);
                owned = new ArrayList<>(scope.tasks);
                inbound = new ArrayList<>(scope.externalCancellation);
                scope.externalCancellation.clear();
            }
            // Registration detach may call host code or throw. Postpone it
            // until no descendant has outstanding cancellation admission.
            toDetach.addAll(inbound);
            pending.addAll(children);
            for (OresTask<?> task : owned) {
                Runnable notification = task.cancelDeferredNotification();
                if (notification != null) notifications.add(notification);
            }
        }
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
            joining = true;
            return new CloseFrame(this,
                    new ArrayList<>(childScopes), new ArrayList<>(tasks),
                    new ArrayList<>(pendingCompilerFrames));
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
            // Revocation has already won for all children. Never abandon
            // owned physical frames simply because a host notification fails.
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
            // A concurrent admission can be inside scheduler.start() while
            // close() takes the snapshot. Its reserved fence remains joinable
            // even if no OresTask handle has been bound yet.
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
