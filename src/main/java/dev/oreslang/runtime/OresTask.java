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
    private final OresFuture<T> completion;

    OresTask(OresFuture<T> completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    @Override
    public OresFuture<T> getAwaited() {
        return completion;
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
        return completion;
    }

    public boolean isDone() {
        return completion.isDone();
    }

    public boolean isCancelled() {
        return completion.isCancelled();
    }

    public boolean cancel() {
        return completion.cancel(true);
    }
}
