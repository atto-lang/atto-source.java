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
        final List<OresTaskScope> scopes;
        final List<OresTask<?>> owned;
        synchronized (gate) {
            if (cancelled) return false;
            cancelled = true;
            scopes = new ArrayList<>(childScopes);
            owned = new ArrayList<>(tasks);
        }
        // Snapshot first, then revoke outside the admission lock. Once the
        // snapshot exists, new children cannot be admitted.
        for (OresTaskScope scope : scopes) scope.cancel();
        for (OresTask<?> task : owned) task.cancel();
        return true;
    }

    /** Cancel and join all descendants before releasing the scope owner. */
    @Override
    public void close() {
        if (ActorRuntime.inActorExecution() || AsyncRuntime.isAsyncCarrierThread()) {
            throw new IllegalStateException(
                    "TaskScope.close cannot block a guest actor or task carrier");
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
        try {
            for (OresTaskScope scope : scopes) scope.close();
            for (OresTask<?> task : owned) AsyncRuntime.await(task.getDone());
        } catch (RuntimeException | Error failure) {
            closeFailure = failure;
            throw failure;
        } finally {
            fullyJoined.countDown();
            if (closeFailure == null && parent != null) {
                synchronized (parent.gate) {
                    parent.childScopes.remove(this);
                }
            }
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
