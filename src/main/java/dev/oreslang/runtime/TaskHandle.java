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

    private final TaskCancellation cancellation = new TaskCancellation();
    // Observation only: cancelling getDone() must never forge a quiescence
    // witness or suppress the scheduler's eventual terminal outcome.
    private final OresFuture<TaskOutcome<T>> done =
            new OresFuture<>(() -> false, () -> { });
    private final AtomicReference<OresFuture<T>> result = new AtomicReference<>();
    private final AtomicReference<TaskOutcome<T>> settledOutcome = new AtomicReference<>();
    private final AtomicBoolean quiescent = new AtomicBoolean();
    private final AtomicBoolean published = new AtomicBoolean();
    private final TaskCancellation.Subscription revoker;

    private TaskHandle() {
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
        return cancellation.cancel(reason);
    }

    public boolean cancel() {
        return cancellation.cancel();
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

    private void onSettled(T value, Throwable failure) {
        final TaskOutcome<T> outcome;
        if (failure == null) {
            outcome = new Success<>(value);
        } else if (failure instanceof CancellationException) {
            outcome = new Cancelled<>(Objects.requireNonNullElse(
                    cancellation.cancellationReason(), "cancelled"));
        } else {
            outcome = new Failed<>(OresFuture.unwrap(failure));
        }
        settledOutcome.compareAndSet(null, outcome);
        maybePublish();
    }

    private void maybePublish() {
        TaskOutcome<T> outcome = settledOutcome.get();
        if (!quiescent.get() || outcome == null) return;
        if (!published.compareAndSet(false, true)) return;
        revoker.close();
        done.completeFromRuntime(outcome);
    }

    /**
     * Trusted scheduler factory. This staging API deliberately creates no
     * source-visible Task or implicit actor scope. Parent ownership will be
     * wired by the subsequent explicit TaskScope/actor integration.
     */
    static <T> TaskHandle<T> start(OresScheduler scheduler, OresScheduler.Task<T> task) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        TaskHandle<T> handle = new TaskHandle<>();
        OresFuture<T> source;
        try {
            source = scheduler.start(resume -> {
                if (handle.cancellation.isCancelled()) {
                    throw new CancellationException("task cancelled before resume");
                }
                return task.resume(resume);
            }, handle::onQuiescent);
        } catch (RuntimeException | Error failure) {
            handle.revoker.close();
            throw failure;
        }

        if (!handle.result.compareAndSet(null, source)) {
            throw new IllegalStateException("TaskHandle result bound more than once");
        }
        source.whenCompleteRuntime(handle::onSettled);
        // Cancellation can win before bind, after bind, or during subscriber
        // registration. The second check makes the early request observable.
        if (handle.cancellation.isCancelled()) source.cancel(false);
        return handle;
    }
}
