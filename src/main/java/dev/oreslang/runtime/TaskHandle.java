package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host/runtime bridge for a cancellable scheduler task.
 *
 * <p>The existing result Future can settle on cancellation while its guest
 * continuation is still running. {@link #getDone()} has a distinct completion
 * barrier: it publishes only after BOTH the result is terminal and the owning
 * OresScheduler reports that the guest turn is quiescent. This is not a
 * source-language Task type, nor a substitute for actor/tenant permission
 * checking or structured child-scope finalization.</p>
 */
public final class TaskHandle<T> {
    /** Terminal outcome after scheduler-confirmed quiescence. */
    public sealed interface TaskOutcome<T>
            permits Success, Cancelled, Failed { }

    public record Success<T>(T value) implements TaskOutcome<T> { }

    public record Cancelled<T>(String reason) implements TaskOutcome<T> {
        public Cancelled {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Failed<T>(Throwable failure) implements TaskOutcome<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * Only scheduler lowering can create this marker. A user-raised
     * CancellationException must remain Failed if it wins terminal settlement.
     * A cancellation request already marked before Future binding must still
     * report Cancelled even if a first resume races the binding path.
     */
    private static final class CancelledAtResume extends CancellationException {
        CancelledAtResume() {
            super("task cancelled before scheduler resume");
        }
    }

    private final TaskCancellation cancellation;
    // Observation only: cancelling getDone() must never forge a quiescence
    // witness or suppress the scheduler's eventual terminal outcome.
    private final OresFuture<TaskOutcome<T>> done =
            new OresFuture<>(() -> false, () -> { });
    private final AtomicReference<OresFuture<T>> result = new AtomicReference<>();
    private final AtomicReference<TaskOutcome<T>> settledOutcome = new AtomicReference<>();
    private final AtomicBoolean quiescent = new AtomicBoolean();
    private final AtomicBoolean published = new AtomicBoolean();
    private final TaskCancellation.Subscription revoker;

    private TaskHandle(TaskCancellation parent) {
        cancellation = parent == null ? new TaskCancellation() : parent.child();
        // The source Future might not exist when cancellation arrives. bind()
        // rechecks the token after publication, and the admission wrapper
        // checks it at every compiler-generated resume boundary.
        revoker = cancellation.onRevoke(this::cancelResultIfBound);
    }

    /** The result Future may become terminal before the physical task exits. */
    public OresFuture<T> result() {
        OresFuture<T> current = result.get();
        if (current == null) {
            throw new IllegalStateException("task admission is not complete");
        }
        return current;
    }

    /**
     * Nonparking observation suitable for Oreslang await lowering. Unlike
     * result(), completion is held until guest execution has physically left
     * its scheduler turn. No scheduler carrier may block on its host get/join.
     */
    public OresFuture<TaskOutcome<T>> getDone() {
        return done;
    }

    /** Requests cancellation; never claims the guest turn has exited. */
    public boolean cancel(String reason) {
        // Cancellation is a request against live work. A terminal result is
        // immutable even when its physical quiescence notification is pending.
        OresFuture<T> current = result.get();
        if (done.isDone() || (current != null && current.isDone())) return false;
        return cancellation.cancel(reason);
    }

    public boolean cancel() {
        return cancel("cancelled");
    }

    public boolean isCancellationRequested() {
        return cancellation.isCancelled();
    }

    private void cancelResultIfBound() {
        OresFuture<T> current = result.get();
        if (current != null) current.cancel(false);
    }

    private void onQuiescent() {
        quiescent.set(true);
        maybePublish();
    }

    private void onSettled(OresFuture<T> source, T value, Throwable failure) {
        final TaskOutcome<T> outcome;
        Throwable unwrapped = failure == null ? null : OresFuture.unwrap(failure);
        if (unwrapped == null) {
            outcome = new Success<>(value);
        } else if (source.isCancelled() || unwrapped instanceof CancelledAtResume) {
            outcome = new Cancelled<>(Objects.requireNonNullElse(
                    cancellation.cancellationReason(), "cancelled"));
        } else {
            // A guest may throw CancellationException without there being a
            // runtime cancellation request: that is a failure, not proof of
            // cancellation, and must not silently hide a task bug.
            outcome = new Failed<>(unwrapped);
        }
        settledOutcome.compareAndSet(null, outcome);
        maybePublish();
    }

    private void maybePublish() {
        TaskOutcome<T> outcome = settledOutcome.get();
        if (!quiescent.get() || outcome == null) return;
        if (!published.compareAndSet(false, true)) return;
        revoker.close();
        // The scheduler has confirmed physical quiescence and the result
        // has settled. Unlink the completed task from a long-lived scope
        // before notifying host subscribers, which can block synchronously.
        cancellation.retire();
        done.completeFromRuntime(outcome);
    }

    /**
     * Trusted scheduler factory. This staging API deliberately creates no
     * source-visible Task or implicit actor scope. Parent ownership will be
     * wired by the subsequent explicit TaskScope/actor integration.
     */
    static <T> TaskHandle<T> start(OresScheduler scheduler, OresScheduler.Task<T> task) {
        return start(scheduler, task, null);
    }

    static <T> TaskHandle<T> start(
            OresScheduler scheduler, OresScheduler.Task<T> task,
            TaskCancellation parent) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        TaskHandle<T> handle = new TaskHandle<>(parent);
        OresFuture<T> source;
        try {
            source = scheduler.start(resume -> {
                if (handle.cancellation.isCancelled()) {
                    throw new CancelledAtResume();
                }
                return task.resume(resume);
            }, handle::onQuiescent);
        } catch (RuntimeException | Error failure) {
            handle.revoker.close();
            handle.cancellation.cancel("admission rejected");
            throw failure;
        }

        if (!handle.result.compareAndSet(null, source)) {
            throw new IllegalStateException("TaskHandle result bound more than once");
        }
        source.whenCompleteRuntime((value, failure) ->
                handle.onSettled(source, value, failure));
        // Cancellation can win before bind, after bind, or during subscriber
        // registration. The second check makes the early request observable.
        if (handle.cancellation.isCancelled()) source.cancel(false);
        return handle;
    }
}
