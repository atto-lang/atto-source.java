package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Context-owned handle for one scheduled Oreslang Task.
 *
 * <p>A Task is a lightweight computation, not an actor: it owns no mailbox,
 * identity, or permission to read an actor's private mutable state. The
 * compiler must enforce capture ownership before it can expose this to guest
 * source code. The current executor uses virtual-thread compatibility
 * carriers; no OS thread allocation or M:N scheduling policy is specified by
 * this handle.</p>
 *
 * <p>Cancellation requests propagate to the owning OresFuture. The owning
 * AsyncRuntime remains responsible for draining its carrier before context
 * teardown. This class does not grant a detached lifetime.</p>
 */
public final class OresTask<T> implements Awaitable<T> {
    private volatile OresFuture<T> completion;
    private final OresFuture<?> quiescence;

    OresTask(OresFuture<T> completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
        // Scheduler Futures can cancel before an in-flight guest turn retires.
        // Never mistake Future settlement for physical quiescence.
        this.quiescence = null;
    }

    OresTask(OresFuture<T> completion, OresFuture<?> quiescence) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.quiescence = Objects.requireNonNull(quiescence, "quiescence");
    }

    /**
     * Pre-admission owner shell. The scope allocates this before scheduler
     * admission so OOME cannot create an executable Task with no owner record.
     */
    OresTask(OresFuture<?> quiescence) {
        this.completion = null;
        this.quiescence = Objects.requireNonNull(quiescence, "quiescence");
    }

    void attachCompletion(OresFuture<T> admitted) {
        Objects.requireNonNull(admitted, "admitted");
        if (completion != null)
            throw new IllegalStateException("Task completion already attached");
        completion = admitted;
    }

    OresFuture<?> quiescenceFence() {
        return quiescence;
    }

    private OresFuture<T> completion() {
        OresFuture<T> current = completion;
        if (current == null)
            throw new IllegalStateException("Task has not completed scheduler admission");
        return current;
    }

    @Override
    public OresFuture<T> getAwaited() {
        return completion();
    }

    /**
     * Explicit observation projection for callers that need Future<T>.
     * This does not launch another task, change its owner, or implicitly
     * flatten a Future<Task<T>>. The original owning runtime remains
     * responsible for its lifecycle.
     *
     * This returns the SAME cancelable completion capability: cancelling the
     * projected OresFuture cancels the Task. It is not a read-only observer
     * and must never cross trust domains without explicit cancel authority.
     */
    public OresFuture<T> asFuture() {
        return completion();
    }

    public boolean isDone() {
        return completion().isDone();
    }

    public boolean isCancelled() {
        return completion().isCancelled();
    }

    public boolean cancel() {
        return completion().cancel(true);
    }

    /** Internal scope path: revoke now, deliver host waiters after siblings. */
    Runnable cancelDeferredNotification() {
        return completion().cancelDeferredNotification();
    }

    /**
     * A fresh, independently cancellable observation of physical Task exit.
     * Cancelling this observer never cancels the producer or another observer.
     * Unlike isDone(), this waits for the host turn to return (including
     * cleanup). For scheduler frames, terminal publication is already fenced
     * on quiescence. Task result cancellation/failure is not rethrown here;
     * failure of the trusted physical retirement fence IS propagated so
     * the owning scope cannot report successful resource cleanup.
     */
    public OresFuture<Void> getDone() {
        if (quiescence == null) throw new UnsupportedOperationException(
                "Task quiescence is unavailable until its scheduler supplies an exit fence");
        OresFuture<Void> observer = new OresFuture<>();
        OresFuture.RuntimeWaiterRegistration<?> registration =
                quiescence.whenCompleteRuntimeCancellable((ignored, failure) -> {
                    if (failure == null) observer.completeFromRuntime(null);
                    else observer.failFromRuntime(failure);
                });
        observer.whenCompleteRuntime((value, failure) -> registration.detach());
        return observer;
    }

    /** Request cancellation, then return a nonblocking cleanup/join barrier. */
    public OresFuture<Void> cancelAndJoin() {
        OresFuture<Void> barrier = getDone();
        cancel();
        return barrier;
    }

    public boolean isQuiescent() {
        if (quiescence == null) throw new UnsupportedOperationException(
                "Task quiescence is unavailable until its scheduler supplies an exit fence");
        return quiescence.isDone();
    }
}
