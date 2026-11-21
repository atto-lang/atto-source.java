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
    private final TaskCancellation cancellation = new TaskCancellation();
    private final Object admissionLock = new Object();
    private final OresFuture<Void> done = new OresFuture<>(() -> false, () -> { });
    private boolean sealed;
    private int active;
    // First trusted retirement failure poisons this scope. We intentionally
    // keep the corresponding join slot live because memory authority was not
    // retired, but we also stop admitting fresh children and revoke siblings.
    private Throwable retirementFailure;

    /**
     * Trusted compiler/runtime bridge for one pre-admitted memory reservation.
     * The two lifecycle edges are deliberately distinct: scheduler rejection
     * proves that no Task ever existed, while physical retirement receives the
     * exact scope and exact TaskHandle whose getDone() witness fired.
     */
    interface RetirementHook<T> {
        void retireNeverStarted(TaskScope scope);
        void retireAfterPhysicalQuiescence(TaskScope scope, TaskHandle<T> child);
    }

    private static final RetirementHook<Object> NOOP_RETIREMENT =
            new RetirementHook<>() {
                @Override
                public void retireNeverStarted(TaskScope scope) { }

                @Override
                public void retireAfterPhysicalQuiescence(
                        TaskScope scope, TaskHandle<Object> child) { }
            };

    @SuppressWarnings("unchecked")
    private static <T> RetirementHook<T> noopRetirement() {
        return (RetirementHook<T>) NOOP_RETIREMENT;
    }

    TaskScope(OresScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Atomically reserve a child slot or reject a sealed scope. */
    public <T> TaskHandle<T> startTask(OresScheduler.Task<T> task) {
        return startTask(task, noopRetirement());
    }

    /**
     * Runtime/compiler-only retirement hook. Scheduler rejection and physical
     * quiescence are separate methods so a never-started reservation cannot be
     * mistaken for a completed child. The physical path receives this exact
     * TaskScope and exact TaskHandle, giving the compiler/runtime bridge the
     * identities it must validate before releasing memory authority.
     *
     * <p>Package-private by design: guest/source code cannot supply a hook that
     * manufactures release authority. A hook failure leaves the scope join slot
     * live (fail closed) rather than publishing premature completion.
     */
    <T> TaskHandle<T> startTask(
            OresScheduler.Task<T> task,
            RetirementHook<T> retirementHook) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retirementHook, "retirement hook");
        synchronized (admissionLock) {
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
            // TaskHandle.start only throws before a scheduler Task has been
            // returned/admitted for execution. If compiler lowering reserved a
            // loan before this call, there will be no getDone() callback to
            // retire it. Retire the never-started reservation here.
            try {
                retirementHook.retireNeverStarted(this);
            } catch (RuntimeException | Error retirementError) {
                admissionFailure.addSuppressed(retirementError);
                poisonAfterRetirementFailure(retirementError);
                // Keep this reserved join slot live: neither execution nor
                // memory-authority retirement produced a trustworthy witness.
                throw admissionFailure;
            }

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
        child.getDone().whenCompleteRuntime((outcome, failure) -> {
            // TaskHandle.getDone() publishes only after physical scheduler
            // quiescence plus descendant retirement. Do not move this hook to
            // result(), cancel(), or cancellation-token retirement.
            try {
                retirementHook.retireAfterPhysicalQuiescence(this, child);
            } catch (RuntimeException | Error retirementError) {
                poisonAfterRetirementFailure(retirementError);
                return;
            }
            childDone();
        });
        return child;
    }

    private void poisonAfterRetirementFailure(Throwable failure) {
        Objects.requireNonNull(failure, "retirement failure");
        synchronized (admissionLock) {
            if (retirementFailure == null) retirementFailure = failure;
            sealed = true;
        }
        // Do not release the failed child's join slot. Revoke any siblings so
        // they can drain to their own physical quiescence without allowing
        // more work into an invariant-broken scope.
        cancellation.cancel("task loan retirement failed");
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

    public boolean isCancellationRequested() {
        return cancellation.isCancelled();
    }

    /** Runtime diagnostics: completed child tokens must not be retained. */
    int liveCancellationChildren() {
        return cancellation.ownedChildCount();
    }

    /** First trusted loan-retirement failure; null while the scope is healthy. */
    Throwable retirementFailure() {
        synchronized (admissionLock) {
            return retirementFailure;
        }
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
