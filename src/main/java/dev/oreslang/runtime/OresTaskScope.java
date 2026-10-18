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
    private final AsyncRuntime runtime;
    private final OresTaskScope parent;
    private final Object gate = new Object();
    private final Set<OresTask<?>> tasks = new HashSet<>();
    private final Set<OresTaskScope> childScopes = new HashSet<>();
    /** Directed cancellation observers; do not carry lifecycle ownership. */
    private final List<OresCancellationToken.Registration> externalCancellation =
            new ArrayList<>();
    private final CountDownLatch fullyJoined = new CountDownLatch(1);
    private boolean cancelled;
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
        synchronized (gate) {
            ensureAdmitting();
            OresTask<T> child = runtime.submitTask(work);
            tasks.add(child);
            // Host-only observer: child membership is retained through
            // cleanup, NOT merely until the cancelable result is settled.
            child.getDone().whenCompleteRuntime((value, failure) -> {
                synchronized (gate) {
                    tasks.remove(child);
                }
            });
            return child;
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
        synchronized (gate) {
            ensureAdmitting();
            OresFuture<Void> exited = new OresFuture<>();
            OresFuture<T> completion = scheduler.start(frame, () -> {
                // The scheduler has already fenced the guest context/turn.
                // A broken host release cannot be treated as a successful
                // resource retirement; do not silently swallow its error.
                try {
                    onPhysicalExit.run();
                    exited.completeFromRuntime(null);
                } catch (RuntimeException | Error failure) {
                    exited.failFromRuntime(failure);
                }
            });
            OresTask<T> child = new OresTask<>(completion, exited);
            tasks.add(child);
            child.getDone().whenCompleteRuntime((ignored, failure) -> {
                synchronized (gate) {
                    tasks.remove(child);
                }
            });
            return child;
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
        synchronized (gate) {
            if (cancelled) registration.close();
            else externalCancellation.add(registration);
        }
    }



    private void ensureAdmitting() {
        if (cancelled || runtime.isClosed())
            throw new RejectedExecutionException("TaskScope is cancelled or its runtime is closed");
    }

    /** Cancel all owned descendants. Does NOT imply physical quiescence. */
    public boolean cancel() {
        List<Runnable> notifications = new ArrayList<>();
        // First close all child admissions and revoke all Task producers.
        // Future cancellation notifies arbitrary synchronous host waiters;
        // notifying one child before revoking its siblings can otherwise
        // indefinitely delay their cancellation.
        boolean won = cancelTreeIterative(notifications);
        Throwable firstFailure = null;
        for (Runnable notification : notifications) {
            try {
                notification.run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure instanceof Error failure) throw failure;
        return won;
    }

    private boolean cancelTreeIterative(List<Runnable> notifications) {
        ArrayDeque<OresTaskScope> pending = new ArrayDeque<>();
        pending.add(this);
        boolean wonRoot = false;
        while (!pending.isEmpty()) {
            OresTaskScope scope = pending.removeLast();
            final List<OresTaskScope> children;
            final List<OresTask<?>> owned;
            final List<OresCancellationToken.Registration> inbound;
            synchronized (scope.gate) {
                if (scope.cancelled) continue;
                scope.cancelled = true;
                if (scope == this) wonRoot = true;
                children = new ArrayList<>(scope.childScopes);
                owned = new ArrayList<>(scope.tasks);
                inbound = new ArrayList<>(scope.externalCancellation);
                scope.externalCancellation.clear();
            }
            // Sever directed inbound links before delivering host callbacks;
            // a cancelled scope must not remain retained by its old sources.
            for (OresCancellationToken.Registration link : inbound) link.close();
            // Iterative traversal avoids recursive stack exhaustion with
            // deeply nested scope trees. Closure of the admission gate is
            // ordered before task revocation and callback delivery.
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
            joining = true;
            return new CloseFrame(this,
                    new ArrayList<>(childScopes), new ArrayList<>(tasks));
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
        OresFuture.RuntimeWaiterRegistration<Void> registration =
                observed.whenCompleteRuntimeCancellable(
                        (value, failure) -> exited.countDown());
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
