package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Native rx-ores stream abstraction.
 *
 * <p>The substrate is deliberately built above OresFuture and ChannelRuntime:
 * it does not own a scheduler, thread pool, or second channel implementation.
 * Guest callbacks are not executed by producer threads; compiler/language
 * lowering can later bind the runtime subscription to scheduler turns.</p>
 */
public abstract class OresObservable<T> {
    public final OresSubscription<T> subscribe() {
        return Objects.requireNonNull(subscribeFromRuntime(),
                "subscribeFromRuntime returned null Subscription");
    }

    protected abstract OresSubscription<T> subscribeFromRuntime();

    public static <T> OresObservable<T> empty() {
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        return OresFuture.completed(OresNotification.complete());
                    }
                };
            }
        };
    }

    public static <T> OresObservable<T> just(T value) {
        return fromValues(Collections.singletonList(value));
    }

    public static <T> OresObservable<T> fromValues(List<? extends T> values) {
        Objects.requireNonNull(values, "values");
        ArrayList<T> snapshot = new ArrayList<>(values.size());
        for (T value : values) {
            snapshot.add(Objects.requireNonNull(value,
                    "rx-ores finite sources cannot contain null values"));
        }
        return fromIterable(Collections.unmodifiableList(snapshot));
    }

    public static <T> OresObservable<T> fromIterable(Iterable<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private Iterator<? extends T> iterator;
                    private boolean initialized;

                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        try {
                            if (!initialized) {
                                iterator = Objects.requireNonNull(values.iterator(),
                                        "rx-ores iterable returned null iterator");
                                initialized = true;
                            }
                            if (!iterator.hasNext()) {
                                return OresFuture.completed(OresNotification.complete());
                            }
                            T value = Objects.requireNonNull(iterator.next(),
                                    "rx-ores iterable emitted null");
                            return OresFuture.completed(OresNotification.next(value));
                        } catch (Throwable failure) {
                            return OresFuture.failed(failure);
                        }
                    }
                };
            }
        };
    }

    /** Adapt one shared Future into a one-item Observable. */
    public static <T> OresObservable<T> fromFuture(OresFuture<? extends T> future) {
        Objects.requireNonNull(future, "future");
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private boolean emitted;
                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (emitted) return OresFuture.completed(OresNotification.complete());
                        emitted = true;
                        OresFuture<OresNotification<T>> result = new OresFuture<>(
                                () -> { /* shared Future is intentionally not cancelled */ });
                        future.whenCompleteRuntime((value, failure) -> {
                            if (failure != null) {
                                if (future.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
                            } else {
                                try {
                                    result.completeFromRuntime(OresNotification.next(
                                            Objects.requireNonNull(value,
                                                    "rx-ores Future emitted standalone null")));
                                } catch (Throwable e) {
                                    result.failFromRuntime(e);
                                }
                            }
                        });
                        return result;
                    }
                };
            }
        };
    }

    /** Adapt a native Oreslang channel into a hot Observable view. */
    public static <T> OresObservable<T> from(ChannelRuntime.Channel<T> channel) {
        Objects.requireNonNull(channel, "channel");
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private OresFuture<T> pending;

                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        pending = channel.readAsync();
                        OresFuture<OresNotification<T>> result =
                                new OresFuture<>(() -> pending.cancel(true));
                        pending.whenCompleteRuntime((value, failure) -> {
                            if (failure != null) {
                                Throwable cause = OresFuture.unwrap(failure);
                                if (cause instanceof ChannelRuntime.ChannelClosedException) {
                                    result.completeFromRuntime(OresNotification.complete());
                                } else if (pending.isCancelled()) {
                                    result.cancel(true);
                                } else {
                                    result.failFromRuntime(cause);
                                }
                            } else {
                                try {
                                    result.completeFromRuntime(OresNotification.next(
                                            Objects.requireNonNull(value)));
                                } catch (Throwable e) {
                                    result.failFromRuntime(e);
                                }
                            }
                        });
                        return result;
                    }

                    @Override protected void cancelFromRuntime() {
                        OresFuture<T> p = pending;
                        if (p != null) p.cancel(true);
                    }
                };
            }
        };
    }

    public final OresObservable<T> take(int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must be non-negative");
        if (limit == 0) return empty();
        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();
                return new OresSubscription<>() {
                    private int remaining = limit;
                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (remaining == 0) return OresFuture.completed(OresNotification.complete());
                        OresFuture<OresNotification<T>> pull = inner.next();
                        OresFuture<OresNotification<T>> result = new OresFuture<>(
                                () -> pull.cancel(true));
                        pull.whenCompleteRuntime((n, failure) -> {
                            if (failure != null) {
                                if (pull.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
                                return;
                            }
                            if (n.isComplete()) {
                                remaining = 0;
                                result.completeFromRuntime(n);
                                return;
                            }
                            remaining--;
                            result.completeFromRuntime(n);
                            if (remaining == 0) inner.cancel();
                        });
                        return result;
                    }
                    @Override protected void cancelFromRuntime() { inner.cancel(); }
                };
            }
        };
    }

    public final OresObservable<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();
                return new OresSubscription<>() {
                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        return pullMatching(inner);
                    }
                    private OresFuture<OresNotification<T>> pullMatching(OresSubscription<T> sub) {
                        OresFuture<OresNotification<T>> pull = sub.next();
                        OresFuture<OresNotification<T>> result = new OresFuture<>(
                                () -> pull.cancel(true));
                        pull.whenCompleteRuntime((n, failure) -> {
                            if (failure != null) {
                                if (pull.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
                                return;
                            }
                            if (n.isComplete()) {
                                result.completeFromRuntime(n);
                                return;
                            }
                            boolean accepted;
                            try { accepted = predicate.test(n.value()); }
                            catch (Throwable e) { result.failFromRuntime(e); sub.cancel(); return; }
                            if (accepted) {
                                result.completeFromRuntime(n);
                            } else {
                                // Re-arm only after the prior pull has settled.
                                pullMatching(sub).whenCompleteRuntime((next, nextFailure) -> {
                                    if (nextFailure != null) {
                                        result.failFromRuntime(OresFuture.unwrap(nextFailure));
                                    } else if (next.isComplete()) {
                                        result.completeFromRuntime(next);
                                    } else {
                                        result.completeFromRuntime(next);
                                    }
                                });
                            }
                        });
                        return result;
                    }
                    @Override protected void cancelFromRuntime() { inner.cancel(); }
                };
            }
        };
    }

    public final <R> OresObservable<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override protected OresSubscription<R> subscribeFromRuntime() {
                OresSubscription<T> inner = upstream.subscribe();
                return new OresSubscription<>() {
                    @Override protected OresFuture<OresNotification<R>> nextFromRuntime() {
                        OresFuture<OresNotification<T>> pull = inner.next();
                        OresFuture<OresNotification<R>> result = new OresFuture<>(
                                () -> pull.cancel(true));
                        pull.whenCompleteRuntime((n, failure) -> {
                            if (failure != null) {
                                if (pull.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
                                return;
                            }
                            if (n.isComplete()) {
                                result.completeFromRuntime(OresNotification.complete());
                                return;
                            }
                            try {
                                result.completeFromRuntime(OresNotification.next(
                                        Objects.requireNonNull(mapper.apply(n.value()),
                                                "rx-ores map produced standalone null")));
                            } catch (Throwable e) {
                                result.failFromRuntime(e);
                            }
                        });
                        return result;
                    }
                    @Override protected void cancelFromRuntime() { inner.cancel(); }
                };
            }
        };
    }

    /**
     * Select-aware merge. It is built directly on native nb-select machinery
     * exposed by ChannelRuntime.SelectSet; no Rx scheduler is introduced.
     */
    @SafeVarargs
    public static <T> OresObservable<T> merge(OresObservable<? extends T>... sources) {
        Objects.requireNonNull(sources, "sources");
        if (sources.length == 0) return empty();
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                List<OresSubscription<? extends T>> subs = new ArrayList<>();
                for (OresObservable<? extends T> source : sources) {
                    subs.add(source.subscribe());
                }
                return new OresSubscription<>() {
                    private boolean[] done = new boolean[sources.length];
                    private int remaining = sources.length;

                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        List<OresFuture<OresNotification<? extends T>>> pulls = new ArrayList<>();
                        List<Integer> indices = new ArrayList<>();
                        for (int i = 0; i < subs.size(); i++) {
                            if (!done[i]) {
                                @SuppressWarnings("unchecked")
                                OresFuture<OresNotification<? extends T>> p =
                                        (OresFuture<OresNotification<? extends T>>) (OresFuture<?>) subs.get(i).next();
                                pulls.add(p);
                                indices.add(i);
                            }
                        }
                        if (pulls.isEmpty()) return OresFuture.completed(OresNotification.complete());

                        // SelectSet is the native nonblocking registration primitive.
                        // Here we use channel-backed sources directly where possible;
                        // arbitrary Observable pulls remain Future-based.
                        OresFuture<OresNotification<T>> result = new OresFuture<>();
                        final boolean[] settled = {false};
                        for (int k = 0; k < pulls.size(); k++) {
                            final int sourceIndex = indices.get(k);
                            pulls.get(k).whenCompleteRuntime((n, failure) -> {
                                if (settled[0]) return;
                                synchronized (settled) {
                                    if (settled[0]) return;
                                    settled[0] = true;
                                }
                                if (failure != null) {
                                    result.failFromRuntime(OresFuture.unwrap(failure));
                                } else if (n.isComplete()) {
                                    done[sourceIndex] = true;
                                    remaining--;
                                    if (remaining == 0) result.completeFromRuntime(OresNotification.complete());
                                    else result.failFromRuntime(new IllegalStateException(
                                            "rx-ores merge requires a new demand after a source completes"));
                                } else {
                                    @SuppressWarnings("unchecked") T value = (T) n.value();
                                    result.completeFromRuntime(OresNotification.next(value));
                                }
                            });
                        }
                        return result;
                    }

                    @Override protected void cancelFromRuntime() {
                        for (OresSubscription<? extends T> sub : subs) sub.cancel();
                    }
                };
            }
        };
    }

    public final OresFuture<T> first() {
        OresSubscription<T> sub = subscribe();
        OresFuture<T> result = new OresFuture<>(() -> sub.cancel());
        OresFuture<OresNotification<T>> pull = sub.next();
        pull.whenCompleteRuntime((n, failure) -> {
            if (failure != null) {
                if (pull.isCancelled()) result.cancel(true);
                else result.failFromRuntime(OresFuture.unwrap(failure));
                sub.cancel();
                return;
            }
            if (n.isComplete()) {
                result.failFromRuntime(new NoSuchElementException(
                        "rx-ores first() observed an empty stream"));
                sub.cancel();
                return;
            }
            result.completeFromRuntime(n.value());
            sub.cancel();
        });
        return result;
    }

    public final OresFuture<List<T>> collect() {
        OresSubscription<T> sub = subscribe();
        OresFuture<List<T>> result = new OresFuture<>(() -> sub.cancel());
        ArrayList<T> values = new ArrayList<>();
        collectNext(sub, values, result);
        return result;
    }

    private static <T> void collectNext(
            OresSubscription<T> sub, ArrayList<T> values, OresFuture<List<T>> result) {
        if (result.isDone()) return;
        OresFuture<OresNotification<T>> pull = sub.next();
        pull.whenCompleteRuntime((n, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                if (pull.isCancelled()) result.cancel(true);
                else result.failFromRuntime(OresFuture.unwrap(failure));
                sub.cancel();
                return;
            }
            if (n.isComplete()) {
                result.completeFromRuntime(Collections.unmodifiableList(new ArrayList<>(values)));
                sub.cancel();
                return;
            }
            values.add(n.value());
            collectNext(sub, values, result);
        });
    }
}
