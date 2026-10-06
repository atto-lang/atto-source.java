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
    /**
     * Non-null only for the native channel adapter. Kept runtime-internal so
     * merge/select can use native nb-select without exposing channel identity
     * as part of the general Observable contract.
     */
    protected ChannelRuntime.Channel<T> nativeChannel() { return null; }

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
            @Override protected ChannelRuntime.Channel<T> nativeChannel() {
                return channel;
            }

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
     * Merge channel-backed sources through the native nonblocking select
     * substrate. A mixed/non-channel source falls back to its Future pulls.
     */
    @SafeVarargs
    public static <T> OresObservable<T> merge(OresObservable<? extends T>... sources) {
        Objects.requireNonNull(sources, "sources");
        for (OresObservable<? extends T> source : sources) Objects.requireNonNull(source, "source");
        if (sources.length == 0) return empty();

        boolean allChannels = true;
        for (OresObservable<? extends T> source : sources) {
            if (source.nativeChannel() == null) { allChannels = false; break; }
        }

        if (allChannels) {
            return new OresObservable<>() {
                @Override protected OresSubscription<T> subscribeFromRuntime() {
                    return new OresSubscription<>() {
                        private final boolean[] done = new boolean[sources.length];
                        private int remaining = sources.length;
                        private OresFuture<ChannelRuntime.SelectResult> activeSelect;

                        @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                            if (remaining == 0) return OresFuture.completed(OresNotification.complete());

                            List<ChannelRuntime.SelectCase> cases = new ArrayList<>();
                            List<Integer> indices = new ArrayList<>();
                            for (int i = 0; i < sources.length; i++) {
                                if (!done[i]) {
                                    cases.add(ChannelRuntime.read(sources[i].nativeChannel()));
                                    indices.add(i);
                                }
                            }

                            ChannelRuntime.SelectSet set = new ChannelRuntime.SelectSet(cases);
                            OresFuture<ChannelRuntime.SelectResult> selected = set.selectAsync();
                            activeSelect = selected;
                            OresFuture<OresNotification<T>> result =
                                    new OresFuture<>(() -> selected.cancel(true));
                            selected.whenCompleteRuntime((selection, failure) -> {
                                activeSelect = null;
                                if (failure != null) {
                                    Throwable cause = OresFuture.unwrap(failure);
                                    if (selected.isCancelled()) {
                                        result.cancel(true);
                                    } else if (cause instanceof ChannelRuntime.ChannelClosedException) {
                                        for (int i = 0; i < sources.length; i++) {
                                            ChannelRuntime.Channel<? extends T> ch = sources[i].nativeChannel();
                                            if (ch.isClosed() && ch.isEmpty()) done[i] = true;
                                        }
                                        remaining = 0;
                                        for (boolean value : done) if (!value) remaining++;
                                        if (remaining == 0) {
                                            result.completeFromRuntime(OresNotification.complete());
                                        } else {
                                            OresFuture<OresNotification<T>> next = nextFromRuntime();
                                            next.whenCompleteRuntime((n, nextFailure) -> {
                                                if (nextFailure != null) result.failFromRuntime(OresFuture.unwrap(nextFailure));
                                                else if (next.isCancelled()) result.cancel(true);
                                                else result.completeFromRuntime(n);
                                            });
                                        }
                                    } else {
                                        result.failFromRuntime(cause);
                                    }
                                    return;
                                }

                                int sourceIndex = indices.get(selection.index());
                                if (selection.operation() != ChannelRuntime.SelectOperation.READ) {
                                    result.failFromRuntime(new IllegalStateException(
                                            "rx-ores merge selected a non-read channel case"));
                                    return;
                                }

                                Object value = selection.value();
                                if (value == null) {
                                    done[sourceIndex] = true;
                                    remaining--;
                                    if (remaining == 0) {
                                        result.completeFromRuntime(OresNotification.complete());
                                    } else {
                                        // A closed channel is simply removed from
                                        // the merge set; the next downstream pull
                                        // performs the next native nb-select.
                                        result.failFromRuntime(new IllegalStateException(
                                                "rx-ores native select reported closed READ without terminal wake"));
                                    }
                                    return;
                                }

                                @SuppressWarnings("unchecked")
                                T typed = (T) value;
                                result.completeFromRuntime(OresNotification.next(typed));
                            });
                            return result;
                        }

                        @Override protected void cancelFromRuntime() {
                            OresFuture<ChannelRuntime.SelectResult> select = activeSelect;
                            if (select != null) select.cancel(true);
                            // Never close caller-owned channels. Cancellation
                            // only unregisters this subscription's native select.
                        }
                    };
                }
            };
        }

        // Generic Observable fallback: race one pull from each source. The
        // winning pull is retained; losing pulls are cancelled so the next
        // demand establishes a fresh registration.
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                List<OresSubscription<? extends T>> subscriptions = new ArrayList<>();
                for (OresObservable<? extends T> source : sources) subscriptions.add(source.subscribe());

                return new OresSubscription<>() {
                    private final boolean[] done = new boolean[sources.length];
                    private int remaining = sources.length;

                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (remaining == 0) return OresFuture.completed(OresNotification.complete());

                        OresFuture<OresNotification<T>> result = new OresFuture<>();
                        List<OresFuture<OresNotification<? extends T>>> pulls = new ArrayList<>();
                        for (int i = 0; i < subscriptions.size(); i++) {
                            if (!done[i]) {
                                @SuppressWarnings("unchecked")
                                OresFuture<OresNotification<? extends T>> pull =
                                        (OresFuture<OresNotification<? extends T>>) (OresFuture<?>) subscriptions.get(i).next();
                                pulls.add(pull);
                            } else {
                                pulls.add(null);
                            }
                        }

                        final Object gate = new Object();
                        final boolean[] won = {false};
                        for (int i = 0; i < pulls.size(); i++) {
                            OresFuture<OresNotification<? extends T>> pull = pulls.get(i);
                            if (pull == null) continue;
                            final int index = i;
                            pull.whenCompleteRuntime((notification, failure) -> {
                                synchronized (gate) {
                                    if (won[0]) return;
                                    if (failure != null) {
                                        won[0] = true;
                                        result.failFromRuntime(OresFuture.unwrap(failure));
                                        return;
                                    }
                                    if (notification.isComplete()) {
                                        done[index] = true;
                                        remaining--;
                                        if (remaining == 0) {
                                            won[0] = true;
                                            result.completeFromRuntime(OresNotification.complete());
                                        }
                                        return;
                                    }
                                    won[0] = true;
                                    @SuppressWarnings("unchecked")
                                    T value = (T) notification.value();
                                    result.completeFromRuntime(OresNotification.next(value));
                                    for (int j = 0; j < pulls.size(); j++) {
                                        if (j != index && pulls.get(j) != null) pulls.get(j).cancel(true);
                                    }
                                }
                            });
                        }

                        result.whenCompleteRuntime((ignored, ignoredFailure) -> {
                            if (result.isCancelled()) {
                                for (OresFuture<OresNotification<? extends T>> pull : pulls) {
                                    if (pull != null) pull.cancel(true);
                                }
                            }
                        });
                        return result;
                    }

                    @Override protected void cancelFromRuntime() {
                        for (OresSubscription<? extends T> subscription : subscriptions) subscription.cancel();
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

    /** Convert the stream to a Future that succeeds only when exactly one value exists. */
    public final OresFuture<T> single() {
        OresSubscription<T> sub = subscribe();
        OresFuture<T> result = new OresFuture<>(() -> sub.cancel());
        ArrayList<T> values = new ArrayList<>(2);
        singleNext(sub, values, result);
        return result;
    }

    private static <T> void singleNext(
            OresSubscription<T> sub, ArrayList<T> values, OresFuture<T> result) {
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
                if (values.size() == 1) result.completeFromRuntime(values.get(0));
                else result.failFromRuntime(new NoSuchElementException(
                        "rx-ores single() requires exactly one stream value"));
                sub.cancel();
                return;
            }
            values.add(n.value());
            if (values.size() > 1) {
                result.failFromRuntime(new IllegalStateException(
                        "rx-ores single() observed more than one stream value"));
                sub.cancel();
                return;
            }
            singleNext(sub, values, result);
        });
    }

    /** Convert the last stream value to a Future; completion without a value fails. */
    public final OresFuture<T> last() {
        OresSubscription<T> sub = subscribe();
        OresFuture<T> result = new OresFuture<>(() -> sub.cancel());
        lastNext(sub, result, new Object[] { null }, new boolean[] { false });
        return result;
    }

    private static <T> void lastNext(
            OresSubscription<T> sub,
            OresFuture<T> result,
            Object[] holder,
            boolean[] hasValue) {
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
                if (!hasValue[0]) {
                    result.failFromRuntime(new NoSuchElementException(
                            "rx-ores last() observed an empty stream"));
                } else {
                    @SuppressWarnings("unchecked")
                    T value = (T) holder[0];
                    result.completeFromRuntime(value);
                }
                sub.cancel();
                return;
            }
            holder[0] = n.value();
            hasValue[0] = true;
            lastNext(sub, result, holder, hasValue);
        });
    }

    /**
     * Defer an async/Future-producing operation until subscription. Each
     * subscription gets its own Future producer, matching cold async sources.
     */
    public static <T> OresObservable<T> deferFuture(
            java.util.function.Supplier<? extends OresFuture<? extends T>> producer) {
        Objects.requireNonNull(producer, "producer");
        return new OresObservable<>() {
            @Override protected OresSubscription<T> subscribeFromRuntime() {
                return new OresSubscription<>() {
                    private OresFuture<? extends T> future;
                    private boolean started;
                    @Override protected OresFuture<OresNotification<T>> nextFromRuntime() {
                        if (started) return OresFuture.completed(OresNotification.complete());
                        started = true;
                        try {
                            future = Objects.requireNonNull(producer.get(),
                                    "rx-ores deferred producer returned null Future");
                        } catch (Throwable failure) {
                            return OresFuture.failed(failure);
                        }
                        OresFuture<OresNotification<T>> result = new OresFuture<>(
                                () -> future.cancel(true));
                        future.whenCompleteRuntime((value, failure) -> {
                            if (failure != null) {
                                if (future.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
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
                        if (future != null) future.cancel(true);
                    }
                };
            }
        };
    }

    /**
     * Async flattening primitive: each source value is mapped to one Future
     * and the Future is flattened back into the stream. This is the runtime
     * substrate for a language-level flat_map(async ...), with one in-flight
     * inner Future per subscription turn.
     */
    public final <R> OresObservable<R> flatMapFuture(
            Function<? super T, ? extends OresFuture<? extends R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        OresObservable<T> upstream = this;
        return new OresObservable<>() {
            @Override protected OresSubscription<R> subscribeFromRuntime() {
                OresSubscription<T> outer = upstream.subscribe();
                return new OresSubscription<>() {
                    private OresFuture<? extends R> inner;
                    @Override protected OresFuture<OresNotification<R>> nextFromRuntime() {
                        OresFuture<OresNotification<T>> outerPull = outer.next();
                        OresFuture<OresNotification<R>> result = new OresFuture<>(
                                () -> outerPull.cancel(true));
                        outerPull.whenCompleteRuntime((n, failure) -> {
                            if (failure != null) {
                                if (outerPull.isCancelled()) result.cancel(true);
                                else result.failFromRuntime(OresFuture.unwrap(failure));
                                return;
                            }
                            if (n.isComplete()) {
                                result.completeFromRuntime(OresNotification.complete());
                                return;
                            }
                            try {
                                inner = Objects.requireNonNull(mapper.apply(n.value()),
                                        "rx-ores flatMapFuture mapper returned null Future");
                            } catch (Throwable mapperFailure) {
                                result.failFromRuntime(mapperFailure);
                                return;
                            }
                            inner.whenCompleteRuntime((value, innerFailure) -> {
                                if (innerFailure != null) {
                                    if (inner.isCancelled()) result.cancel(true);
                                    else result.failFromRuntime(OresFuture.unwrap(innerFailure));
                                    return;
                                }
                                try {
                                    result.completeFromRuntime(OresNotification.next(
                                            Objects.requireNonNull(value)));
                                } catch (Throwable e) {
                                    result.failFromRuntime(e);
                                }
                            });
                        });
                        return result;
                    }
                    @Override protected void cancelFromRuntime() {
                        outer.cancel();
                        if (inner != null) inner.cancel(true);
                    }
                };
            }
        };
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
