package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-consumer, pull-based ReadableStream<T> view of an input channel.
 *
 * Obtaining a stream does not consume bytes; acquiring its reader claims an
 * exclusive lease. Releasing that lease never closes a process-owned channel.
 * The stream does not introduce another buffer or make stdin multicast.
 */
public final class ChannelReadableStream<T> {
    private final ChannelRuntime.Channel<T> source;
    private final AtomicBoolean readerClaimed = new AtomicBoolean();

    public ChannelReadableStream(ChannelRuntime.Channel<T> source) {
        this.source = Objects.requireNonNull(source);
    }

    public Reader<T> getReader() {
        if (!readerClaimed.compareAndSet(false, true))
            throw new IllegalStateException("ReadableStream already has an active reader");
        return new Reader<>(this);
    }

    public static final class Reader<T> implements AutoCloseable {
        private final ChannelReadableStream<T> stream;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reader(ChannelReadableStream<T> stream) { this.stream = stream; }

        public OresFuture<T> readAsync() {
            if (closed.get())
                return OresFuture.failed(new IllegalStateException("ReadableStream reader released"));
            return stream.source.readAsync();
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true))
                stream.readerClaimed.set(false);
        }
    }
}
