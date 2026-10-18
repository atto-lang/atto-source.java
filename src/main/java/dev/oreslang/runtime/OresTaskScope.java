package dev.oreslang.runtime;

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
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        synchronized (gate) {
            ensureAdmitting();
            OresFuture<Void> exited = new OresFuture<>();
            OresFuture<T> completion = scheduler.start(
                    frame, () -> exited.completeFromRuntime(null));
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
        boolean won = cancelTree(notifications);
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

    private boolean cancelTree(List<Runnable> notifications) {
        final List<OresTaskScope> scopes;
        final List<OresTask<?>> owned;
        synchronized (gate) {
            if (cancelled) return false;
            cancelled = true;
            scopes = new ArrayList<>(childScopes);
            owned = new ArrayList<>(tasks);
        }
        // Lock-free recursion: no parent/child monitor is held while a child
        // is revoked, and no callback may run before the full cancellation
        // tree has had its revocation opportunity.
        for (OresTaskScope scope : scopes) scope.cancelTree(notifications);
        for (OresTask<?> task : owned) {
            Runnable notification = task.cancelDeferredNotification();
            if (notification != null) notifications.add(notification);
        }
        return true;
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
        cancel();
        final boolean owner;
        final List<OresTaskScope> scopes;
        final List<OresTask<?>> owned;
        synchronized (gate) {
            owner = !joining;
            if (owner) joining = true;
            scopes = owner ? new ArrayList<>(childScopes) : List.of();
            owned = owner ? new ArrayList<>(tasks) : List.of();
        }
        if (!owner) {
            joinExistingClose();
            return;
        }
        Throwable firstFailure = null;
        try {
            for (OresTaskScope scope : scopes) {
                try {
                    scope.close();
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
            for (OresTask<?> task : owned) {
                try {
                    awaitPhysicalExit(task.getDone());
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) firstFailure = failure;
                    else firstFailure.addSuppressed(failure);
                }
            }
            closeFailure = firstFailure;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            if (firstFailure instanceof Error failure) throw failure;
        } finally {
            // Other close callers must see a failure rather than treating an
            // exceptional join as successful physical quiescence.
            closeFailure = firstFailure;
            fullyJoined.countDown();
            if (firstFailure == null && parent != null) {
                synchronized (parent.gate) {
                    parent.childScopes.remove(this);
                }
            }
        }
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
        try {
            fullyJoined.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while joining TaskScope termination", interruption);
        }
        Throwable failure = closeFailure;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }
}
