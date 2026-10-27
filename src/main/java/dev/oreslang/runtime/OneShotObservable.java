package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A limited trusted-host one-shot Observable backed by an already-started
 * Future or a tracked Task result. This is NOT a general reactive Observable
 * or a guest API; no multicast, mapper, cold replay, source-level type proof,
 * or onNext callback execution is implied.
 *
 * <p>The only subscriber is claimed once. This conservative limit prevents
 * a generic, possibly affine T from being observed through two independent
 * consuming aliases before the compiler can prove Copy/share capability.</p>
 */
public final class OneShotObservable<T> {
    /** Explicit cardinality is not an arbitrary dynamic guest string. */
    public enum Mode { FIRST, SINGLE }

    public static final class EmptyObservableError extends IllegalStateException {
        public EmptyObservableError() {
            super("Observable.single requires exactly one item; source completed empty");
        }
    }

    private final OresFuture<T> producer;
    private final boolean eventOnly;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private OneShotObservable(OresFuture<T> producer, boolean eventOnly) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.eventOnly = eventOnly;
    }

    public static <T> OneShotObservable<T> fromFuture(OresFuture<T> producer) {
        return new OneShotObservable<>(producer, false);
    }

    public static <T> OneShotObservable<T> fromTask(TaskHandle<T> producer) {
        return new OneShotObservable<>(
                Objects.requireNonNull(producer, "producer").result(), false);
    }

    public static OneShotObservable<Void> fromVoidFuture(OresFuture<Void> producer) {
        return new OneShotObservable<>(producer, true);
    }

    public static OneShotObservable<Void> fromVoidTask(TaskHandle<Void> producer) {
        return new OneShotObservable<>(
                Objects.requireNonNull(producer, "producer").result(), true);
    }

    /**
     * One subscription observes one producer result; no guest callback is
     * executed here. Returned reduction Future owns *only* its detachable
     * source listener; cancelling it never cancels producer.
     */
    private <R> OresFuture<R> reduce(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "one-shot Observable is single-consumer until result sharing is proven");
        }

        AtomicReference<OresFuture.RuntimeWaiterRegistration<T>> registration =
                new AtomicReference<>();
        OresFuture<R> reduction = new OresFuture<>(() -> {
            OresFuture.RuntimeWaiterRegistration<T> active =
                    registration.getAndSet(null);
            if (active != null) active.detach();
        });

        final OresFuture.RuntimeWaiterRegistration<T> subscribed;
        try {
            subscribed = producer.whenCompleteRuntimeCancellable((value, failure) -> {
                try {
                    if (producer.isCancelled()) {
                        reduction.cancel(false); // authenticated CANCELLED
                    } else if (failure != null) {
                        reduction.failFromRuntime(OresFuture.unwrap(failure));
                    } else if (mode == Mode.FIRST) {
                        @SuppressWarnings("unchecked")
                        R option = (R) ((eventOnly || value == null)
                                ? Optional.<T>empty() : Optional.of(value));
                        reduction.completeFromRuntime(option);
                    } else if (eventOnly || value == null) {
                        reduction.failFromRuntime(new EmptyObservableError());
                    } else {
                        @SuppressWarnings("unchecked")
                        R item = (R) value;
                        reduction.completeFromRuntime(item);
                    }
                } finally {
                    OresFuture.RuntimeWaiterRegistration<T> active =
                            registration.getAndSet(null);
                    if (active != null) active.detach();
                }
            });
        } catch (RuntimeException | Error registrationFailure) {
            reduction.failFromRuntime(registrationFailure);
            return reduction;
        }
        registration.set(subscribed);
        // An already-terminal producer can synchronously run its runtime-only
        // callback before subscribe returns a registration token.
        if (reduction.isDone()) {
            OresFuture.RuntimeWaiterRegistration<T> active =
                    registration.getAndSet(null);
            if (active != null) active.detach();
        }
        return reduction;
    }

    /** Exactly one item and completed source. Empty/void is an error. */
    public OresFuture<T> single() {
        return reduce(Mode.SINGLE);
    }

    /** First item as Some, or empty completion as None. */
    public OresFuture<Optional<T>> first() {
        return reduce(Mode.FIRST);
    }

    /**
     * Exactly one tracked adapter, and one owned reduction subscription.
     * The reduction Future is a detachable observer, not the producer.
     */
    public TaskHandle<T> toTaskSingle(OresScheduler scheduler) {
        return TaskFutureAdapters.fromOwnedObservation(scheduler, single());
    }

    public TaskHandle<Optional<T>> toTaskFirst(OresScheduler scheduler) {
        return TaskFutureAdapters.fromOwnedObservation(scheduler, first());
    }
}
