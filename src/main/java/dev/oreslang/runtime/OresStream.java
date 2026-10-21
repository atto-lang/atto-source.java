package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Oreslang-owned, bounded unicast stream transport (v1).
 *
 * <p>The stream and its underlying channel are separate from the Task that
 * constructs or returns the handle. Completion of that Task never closes the
 * channel or invalidates its buffered data. The stream is NOT a borrowed host
 * iterator, generator activation, or an actor mailbox.</p>
 *
 * <p>Readers compete for values (each value is delivered at most once);
 * this is not a multicast Observable. Capacity and pending registrations are
 * bounded by ChannelRuntime. Explicit close preserves buffered values, then
 * read/write operations fail with ChannelClosedException.</p>
 */
public final class OresStream<T> implements AutoCloseable {
    private final ChannelRuntime.Channel<T> channel;

    public OresStream(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "Stream<T> requires a positive bounded capacity");
        }
        this.channel = new ChannelRuntime.Channel<>(capacity);
    }

    public ChannelRuntime.Channel<T> channel() {
        return channel;
    }

    public boolean isClosed() {
        return channel.isClosed();
    }

    @Override
    public void close() {
        channel.close();
    }
}
