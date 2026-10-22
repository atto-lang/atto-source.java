package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Explicit host/runtime lifetime owner for scheduled Tasks.
 *
 * <p>Closing the scope requests cancellation and does not itself join
 * running children. Cancellation hooks and Future subscribers may execute
 * synchronously, so a bounded nonparking cancellation latency is NOT promised.
 * {@link #getDone()} is an observation-only Future and
 * settles only after the scope is sealed and every admitted TaskHandle has
 * reported physical scheduler quiescence. This class does not expose guest
 * actor/tenant cancellation rights or a source-language TaskScope type.</p>
 */
public final class TaskScope implements AutoCloseable {
    private final OresScheduler scheduler;
    /** Admission authority for actor-affine scopes, checked on every spawn. */
    private final Runnable checkAdmission;
    private final TaskCancellation cancellation = new TaskCancellation();
    private final Object admissionLock = new Object();
    private final OresFuture<Void> done = new OresFuture<>(() -> false, () -> { });
    /** Stop/cancel retries must not spawn unbounded host notifier threads. */
    private final java.util.concurrent.atomic.AtomicBoolean cancelNotifierStarted =
            new java.util.concurrent.atomic.AtomicBoolean();
    private boolean sealed;
    private int active;

    TaskScope(OresScheduler scheduler) {
        this(scheduler, () -> { });
    }

    TaskScope(OresScheduler scheduler, Runnable checkAdmission) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.checkAdmission = Objects.requireNonNull(checkAdmission, "checkAdmission");
    }

    /** Atomically reserve a child slot or reject a sealed scope. */
    public <T> TaskHandle<T> startTask(OresScheduler.Task<T> task) {
        Objects.requireNonNull(task, "task");
        synchronized (admissionLock) {
            // Check owner/stop authority at the same admission boundary as
            // sealed and active, not before another thread can stop the actor.
            checkAdmission.run();
            if (sealed) {
                throw new RejectedExecutionException("TaskScope is sealed");
            }
            // Reservation linearizes against seal/cancel. Keep the monitor
            // free of scheduler dispatch: runtime-owned schedulers may run a
            // complete guest turn synchronously during TaskHandle.start().
            // Never wrap the signed join-slot counter: a negative count
            // could break the quiescence witness and scope accounting.
            if (active == Integer.MAX_VALUE) {
                throw new RejectedExecutionException("TaskScope child count exhausted");
            }
            active++;
        }
        final TaskHandle<T> child;
        try {
            child = TaskHandle.start(scheduler, task, cancellation);
        } catch (RuntimeException | Error admissionFailure) {
            // The reservation is already visible to concurrent seal/cancel;
            // undo it under the same lock, then publish completion outside.
            final boolean publish;
            synchronized (admissionLock) {
                active--;
                publish = sealed && active == 0;
            }
            if (publish) done.completeFromRuntime(null);
            throw admissionFailure;
        }
        // The child may have finished before callback registration. In that
        // case, OresFuture invokes subscribers synchronously; never deliver
        // arbitrary scope completion observers while holding admissionLock.
        // Any seal/cancel racing this interval sees active > 0 and waits for
        // childDone() after its actual scheduler quiescence.
        child.getDone().whenCompleteRuntime((outcome, failure) -> childDone());
        return child;
    }

    private void childDone() {
        boolean publish;
        synchronized (admissionLock) {
            if (active <= 0) {
                throw new IllegalStateException("TaskScope child accounting underflow");
            }
            active--;
            publish = sealed && active == 0;
        }
        // Never deliver arbitrary Future subscribers under the admission lock.
        if (publish) done.completeFromRuntime(null);
    }

    /** Stop admitting new children but let existing children complete normally. */
    public void seal() {
        boolean publish;
        synchronized (admissionLock) {
            sealed = true;
            publish = active == 0;
        }
        if (publish) done.completeFromRuntime(null);
    }

    /** Cancel owned children and seal admission. Does not wait for execution. */
    public boolean cancel(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("reason must contain 1..256 nonblank characters");
        }
        synchronized (admissionLock) {
            sealed = true;
        }
        try {
            return cancellation.cancel(reason);
        } finally {
            boolean publish;
            synchronized (admissionLock) {
                publish = active == 0;
            }
            if (publish) done.completeFromRuntime(null);
        }
    }

    /**
     * Runtime-only actor-stop path. Seal child admission synchronously, then
     * request child cancellation on a host virtual notifier: cancellation
     * hooks and Future subscribers are user callbacks and may block.
     *
     * <p>The parent actor MUST continue waiting on getDone(); launching the
     * cancellation notifier is not a physical-retirement receipt.</p>
     */
    void requestCancelNonblocking(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException(
                    "reason must contain 1..256 nonblank characters");
        }
        synchronized (admissionLock) {
            sealed = true;
        }
        if (!cancelNotifierStarted.compareAndSet(false, true)) return;
        try {
            Thread.ofVirtual().name("ores-actor-scope-cancel")
                    .start(() -> cancel(reason));
        } catch (RuntimeException | Error failure) {
            // No notifier was admitted. A later explicit cancellation attempt
            // may retry, but the parent must continue pinning native memory.
            cancelNotifierStarted.set(false);
            throw failure;
        }
    }

    public boolean isCancellationRequested() {
        return cancellation.isCancelled();
    }

    /** Runtime diagnostics: completed child tokens must not be retained. */
    int liveCancellationChildren() {
        return cancellation.ownedChildCount();
    }

    /**
     * True quiescence witness for the currently admitted children, once the
     * scope is sealed. Callers should await it rather than blocking carriers.
     */
    public OresFuture<Void> getDone() {
        return done;
    }

    /** Requests cancellation without joining; notification hooks may still take time. */
    @Override
    public void close() {
        cancel("scope closed");
    }
}
