package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Explicit Observable -> native Channel bridge.
 *
 * <p>The bridge is pull-driven: each pump step reads one item from the
 * Observable and then uses Channel.writeAsync(), which is the native
 * nb-writech/backpressure boundary. No Rx executor or thread pool is created.</p>
 */
public final class OresObservableChannelBridge<T> implements AutoCloseable {
    private final OresObservable<T> source;
    private final ChannelRuntime.Channel<T> channel;
    private OresSubscription<T> subscription;
    private OresFuture<Void> completion;
    private boolean started;

    public OresObservableChannelBridge(
            OresObservable<T> source,
            ChannelRuntime.Channel<T> channel) {
        this.source = Objects.requireNonNull(source, "source");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public ChannelRuntime.Channel<T> channel() { return channel; }

    public synchronized OresFuture<Void> start() {
        if (started) return completion;
        started = true;
        subscription = source.subscribe();
        completion = new OresFuture<>(this::cancel);
        pump();
        return completion;
    }

    private void pump() {
        if (completion.isDone()) return;
        OresFuture<OresNotification<T>> pull = subscription.next();
        pull.whenCompleteRuntime((notification, failure) -> {
            if (completion.isDone()) return;
            if (failure != null) {
                if (pull.isCancelled()) {
                    completion.cancel(true);
                } else {
                    completion.failFromRuntime(OresFuture.unwrap(failure));
                }
                channel.close(failure);
                return;
            }
            if (notification.isComplete()) {
                channel.close();
                completion.completeFromRuntime(null);
                return;
            }

            OresFuture<Void> write = channel.writeAsync(notification.value());
            write.whenCompleteRuntime((ignored, writeFailure) -> {
                if (completion.isDone()) return;
                if (writeFailure != null) {
                    if (write.isCancelled()) completion.cancel(true);
                    else completion.failFromRuntime(OresFuture.unwrap(writeFailure));
                    subscription.cancel();
                    return;
                }
                pump();
            });
        });
    }

    private boolean cancel() {
        OresSubscription<T> sub;
        synchronized (this) {
            if (completion != null && completion.isDone()) return false;
            sub = subscription;
        }
        if (sub != null) sub.cancel();
        channel.close();
        return true;
    }

    @Override public void close() { cancel(); }
}
