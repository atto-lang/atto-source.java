package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns only channel/select operations created by one suspendable *source*
 * task. Cancellation of the source task must cancel the producer-side
 * channel registration, not merely detach a scheduler Future waiter.
 *
 * General `await future` does not use this gate: the task does not own
 * arbitrary externally supplied Future producers. Source lexical cleanup
 * channel waits remain authorized after cancellation and are not revoked
 * until resumed or explicitly aborted during an exceptional unwind.
 *
 * The AtomicReference publication + cancelled flag double-check closes
 * the race between source await installation and a host cancellation.
 */
public final class SourceChannelWaitOwnership {
    private record Pending(OresFuture<?> future, boolean lexicalCleanup) { }

    private final AtomicReference<Pending> active = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void arm(OresFuture<?> future, boolean lexicalCleanup) {
        Objects.requireNonNull(future, "future");
        Pending pending = new Pending(future, lexicalCleanup);
        if (!active.compareAndSet(null, pending)) {
            throw new IllegalStateException(
                    "source task attempted overlapping owned channel waits");
        }
        // Cancellation may have been published before arm() or immediately
        // after the compareAndSet above. In both orders, one side revokes.
        if (cancelled.get() && !lexicalCleanup) revoke(pending);
    }

    public void cancellationRequested() {
        cancelled.set(true);
        Pending pending = active.get();
        if (pending != null && !pending.lexicalCleanup()) revoke(pending);
    }

    /** Remove the one source-owned registration after its continuation runs. */
    public void resumed(OresFuture<?> future) {
        Objects.requireNonNull(future, "future");
        Pending pending = active.get();
        if (pending != null) {
            if (pending.future() != future) {
                throw new IllegalStateException(
                        "source channel completion belongs to another wait");
            }
            active.compareAndSet(pending, null);
        }
        // A task cancellation may already have revoked this exact wait.
    }

    /** Called only on source task fatal unwind, not during normal cleanup. */
    public void abort() {
        Pending pending = active.getAndSet(null);
        if (pending != null) pending.future().cancel(false);
    }

    private void revoke(Pending pending) {
        if (active.compareAndSet(pending, null)) {
            // Producer-side channel cancellation atomically removes *all*
            // registered loser arms under ChannelRuntime.COORDINATOR.
            pending.future().cancel(false);
        }
    }
}
