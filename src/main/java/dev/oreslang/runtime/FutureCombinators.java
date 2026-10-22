package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Host/runtime plumbing for composition of already-started OresFuture values.
 *
 * <p>These methods observe sources; they never spawn tasks, execute guest code,
 * acquire source cancellation authority, or run a guest continuation from the
 * source completion thread. The returned Future is independently cancellable:
 * cancelling it detaches its observations, not the source operations.</p>
 *
 * <p>This class is NOT a guest-language intrinsic registration. Source-level
 * Future.all and Task.parallel require separately checked lowering.</p>
 */
public final class FutureCombinators {
    private static final int MAX_FAN_IN = 65_536;

    private FutureCombinators() { }

    public enum Status { SUCCESS, FAILURE, CANCELLED }

    public record Outcome<T>(Status status, T value, Throwable failure) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (status == Status.SUCCESS && failure != null) {
                throw new IllegalArgumentException("success must not contain a failure");
            }
            if (status != Status.SUCCESS && failure == null) {
                throw new IllegalArgumentException("failure/cancellation requires a cause");
            }
        }
    }

    private static <T> List<? extends OresFuture<? extends T>> snapshot(
            List<? extends OresFuture<? extends T>> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() > MAX_FAN_IN) {
            throw new IllegalArgumentException("too many Futures in one composition");
        }
        // Reject nulls and take an immutable snapshot BEFORE installing listeners.
        return List.copyOf(inputs);
    }

    private static void detachAll(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations) {
        for (int i = 0; i < registrations.length(); i++) {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    registrations.getAndSet(i, null);
            if (registration != null) registration.detach();
        }
    }

    private static void retainUntilPendingOrDetach(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations,
            int index, OresFuture.RuntimeWaiterRegistration<?> registration,
            OresFuture<?> aggregate) {
        registrations.set(index, registration);
        // A settled source can notify inline before subscription returns; the
        // terminal callback's cleanup may have observed an empty slot.
        if (aggregate.isDone()) {
            registration.detach();
            registrations.compareAndSet(index, registration, null);
        }
    }

    /**
     * Analog of Promise.all for already-running operations. Result order is
     * input order, regardless of completion order. Empty input succeeds.
     *
     * <p>First observed failure fails the aggregate; first observed source
     * cancellation cancels it. Other source operations remain untouched.
     * These are OBSERVATION semantics, not a structured child join/quiescence
     * guarantee. The caller must own a TaskScope for owned child cancellation.</p>
     */
    public static <T> OresFuture<List<T>> all(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) return OresFuture.completed(List.of());
        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<List<T>> aggregate = new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            final int index = i;
            OresFuture<? extends T> source = sources.get(index);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (aggregate.isDone()) return;
                        if (failure != null) {
                            if (source.isCancelled()) {
                                aggregate.cancel(false);
                            } else if (aggregate.failFromRuntime(failure)) {
                                detachAll(registrations);
                            }
                            return;
                        }
                        values.set(index, value);
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<T> ordered = new ArrayList<>(n);
                            for (int j = 0; j < n; j++) {
                                @SuppressWarnings("unchecked")
                                T item = (T) values.get(j);
                                ordered.add(item);
                            }
                            if (aggregate.completeFromRuntime(
                                    Collections.unmodifiableList(ordered))) {
                                detachAll(registrations);
                            }
                        }
                    });
            retainUntilPendingOrDetach(registrations, index, subscription, aggregate);
        }
        return aggregate;
    }

    /**
     * Wait for every input, including failed and cancelled inputs, returning
     * outcomes in input order. Only cancellation of the aggregate itself
     * detaches it early; input failures never short-circuit allSettled.
     */
    public static <T> OresFuture<List<Outcome<T>>> allSettled(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) return OresFuture.completed(List.of());
        AtomicReferenceArray<Outcome<T>> outcomes = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<List<Outcome<T>>> aggregate =
                new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            final int index = i;
            OresFuture<? extends T> source = sources.get(index);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (aggregate.isDone()) return;
                        Status status = failure == null ? Status.SUCCESS
                                : source.isCancelled() ? Status.CANCELLED : Status.FAILURE;
                        outcomes.set(index, new Outcome<>(status, value, failure));
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<Outcome<T>> ordered = new ArrayList<>(n);
                            for (int j = 0; j < n; j++) ordered.add(outcomes.get(j));
                            if (aggregate.completeFromRuntime(List.copyOf(ordered))) {
                                detachAll(registrations);
                            }
                        }
                    });
            retainUntilPendingOrDetach(registrations, index, subscription, aggregate);
        }
        return aggregate;
    }

    /**
     * First observed terminal outcome wins. Subscriptions on already-settled
     * inputs are installed in input order, making their tie-break deterministic.
     * An empty race is rejected rather than creating a forever-pending Future.
     * Losing inputs are never cancelled by this observer.
     */
    public static <T> OresFuture<T> race(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) throw new IllegalArgumentException("race requires at least one Future");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<T> aggregate = new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            OresFuture<? extends T> source = sources.get(i);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        boolean won;
                        if (failure == null) won = aggregate.completeFromRuntime(value);
                        else if (source.isCancelled()) won = aggregate.cancel(false);
                        else won = aggregate.failFromRuntime(failure);
                        if (won) detachAll(registrations);
                    });
            retainUntilPendingOrDetach(registrations, i, subscription, aggregate);
        }
        return aggregate;
    }
}
