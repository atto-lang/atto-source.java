package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted host-only Future -> Task bridge. The producer remains independently
 * owned, while exactly one new scheduler Task awaits its result nonblocking.
 *
 * <p>Never use this helper as source Task<T> admission: guest ownership, affine
 * payloads, permissions, hot-generation fences and source effects still require
 * compiler proof. Adapter getDone() is NOT a producer retirement receipt.</p>
 */
public final class TaskFutureAdapters {
    private TaskFutureAdapters() { }

    public static <T> TaskHandle<T> fromFuture(
            OresScheduler scheduler, OresFuture<T> source) {
        return fromFuture(scheduler, source, false);
    }

    /**
     * For a trusted adapter that uniquely owns the source's observation
     * subscription, release that private subscription when the adapter
     * physically retires. Never use this on a producer-owned Future.
     */
    static <T> TaskHandle<T> fromOwnedObservation(
            OresScheduler scheduler, OresFuture<T> observation) {
        return fromFuture(scheduler, observation, true);
    }

    private static <T> TaskHandle<T> fromFuture(
            OresScheduler scheduler, OresFuture<T> source,
            boolean ownsObservationSubscription) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(source, "source");

        // This bridge is private to the adapter. Its cancellation does not
        // revoke or cancel the upstream Future or its producing Task.
        OresFuture<T> awaited = new OresFuture<>();
        AtomicBoolean entered = new AtomicBoolean();
        OresScheduler.Task<T> waiter = resume -> {
            if (resume.initial()) {
                if (!entered.compareAndSet(false, true)) {
                    throw new IllegalStateException(
                            "Future adapter task admitted its initial turn twice");
                }
                return OresScheduler.await(awaited);
            }
            if (!entered.get()) {
                throw new IllegalStateException(
                        "Future adapter resumed without initial admission");
            }
            Throwable failure = resume.failure();
            if (failure != null) {
                Throwable cause = OresFuture.unwrap(failure);
                if (cause instanceof Error error) throw error;
                if (cause instanceof Exception exception) throw exception;
                throw new IllegalStateException("unsupported Future failure", cause);
            }
            @SuppressWarnings("unchecked")
            T value = (T) resume.value();
            return OresScheduler.done(value);
        };

        // Physical Task admission is bounded by the existing scheduler; no
        // per-adapter executor or dedicated OS thread is created.
        TaskHandle<T> adapter = scheduler.startTracked(waiter);
        AtomicReference<OresFuture.RuntimeWaiterRegistration<T>> registration =
                new AtomicReference<>();
        try {
            OresFuture.RuntimeWaiterRegistration<T> token =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (source.isCancelled()) {
                            // This is an authenticated upstream CANCELLED event.
                            // Cancel the dependent Task through its own tracked
                            // token BEFORE publishing a ready failure to it.
                            adapter.cancel("source Future cancelled");
                            awaited.cancel(false);
                        } else if (failure != null) {
                            awaited.failFromRuntime(OresFuture.unwrap(failure));
                        } else {
                            awaited.completeFromRuntime(value);
                        }
                    });
            registration.set(token);
        } catch (RuntimeException | Error subscriptionFailure) {
            // Failing to attach cannot leave a tracked waiter orphaned.
            awaited.failFromRuntime(subscriptionFailure);
        }

        adapter.getDone().whenCompleteRuntime((outcome, failure) -> {
            OresFuture.RuntimeWaiterRegistration<T> active =
                    registration.getAndSet(null);
            if (active != null) active.detach();
            // Cancellation here is safe ONLY for a bridge-owned projection:
            // its cancellation hook detaches a subscription, not producer.
            if (ownsObservationSubscription) source.cancel(false);
        });
        // A synchronously settled upstream can finish the adapter before
        // publication of its detachable source subscription.
        if (adapter.getDone().isDone()) {
            OresFuture.RuntimeWaiterRegistration<T> active =
                    registration.getAndSet(null);
            if (active != null) active.detach();
            if (ownsObservationSubscription) source.cancel(false);
        }
        return adapter;
    }
}
