package dev.oreslang.runtime;

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
    /**
     * Phase-1 hard admission ceiling. Never silently clamp capacity: callers
     * must explicitly choose a bounded queue smaller than this resource cap.
     * The broader isolate memory budget is a separate runtime admission gate.
     */
    public static final int MAX_CAPACITY = 65_536;
    private final ChannelRuntime.Channel<T> channel;

    public OresStream(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "Stream<T> capacity must be between 1 and " + MAX_CAPACITY);
        }
        this.channel = new ChannelRuntime.Channel<>(capacity);
    }

    /**
     * Runtime ABI for Stream.new<T>(capacity). Compiler checking alone cannot
     * authorize a stale/forged AST or a host invocation with a fractional
     * Number. Accept only actual integral guest representations, without
     * truncation, unsigned saturation or implicit narrowing.
     */
    public static <T> OresStream<T> fromGuestCapacity(Object requested) {
        final int capacity;
        try {
            if (requested instanceof java.math.BigInteger big) {
                capacity = big.intValueExact();
            } else if (requested instanceof Byte || requested instanceof Short
                    || requested instanceof Integer || requested instanceof Long) {
                capacity = Math.toIntExact(((Number) requested).longValue());
            } else {
                throw new IllegalArgumentException(
                        "Stream.new<T> capacity must be an exact integer");
            }
        } catch (ArithmeticException outOfRange) {
            throw new IllegalArgumentException(
                    "Stream.new<T> capacity exceeds supported integer range",
                    outOfRange);
        }
        return new OresStream<>(capacity);
    }

    public ChannelRuntime.Channel<T> channel() {
        return channel;
    }

    public boolean isClosed() {
        return channel.isClosed();
    }

    /**
     * Runtime-only producer failure handoff. The underlying channel retains
     * committed buffered items, then exposes the original cause on its
     * ChannelClosedException after drain. This does not itself bind a Task
     * lifetime to this Stream or grant actor-message transport rights.
     */
    public void close(Throwable producerFailure) {
        channel.close(java.util.Objects.requireNonNull(
                producerFailure, "producer failure"));
    }

    @Override
    public void close() {
        channel.close();
    }
}
