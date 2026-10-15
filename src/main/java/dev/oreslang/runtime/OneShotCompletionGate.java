package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claims a source-continuation completion before invoking its consumer.
 *
 * A cancelled or already completed operation may still produce a late
 * callback. Those callbacks must not re-enter guest code, including when
 * the original consumer threw after taking ownership of its result.
 *
 * <p><b>Scheduling boundary:</b> this gate prevents duplicate invocation,
 * but does not provide an event loop, actor mailbox admission, or asynchronous
 * dispatch. Callers must first arrange for their continuation to run on the
 * appropriate serialized actor lane. Calling deliver directly on a producer
 * thread executes the supplied Runnable on that producer thread.</p>
 */
public final class OneShotCompletionGate {
    private final AtomicBoolean delivered = new AtomicBoolean();

    /**
     * @return true only for the callback that claimed the completion.
     *         Exceptions from the consumer propagate unchanged.
     */
    public boolean deliver(Runnable consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!delivered.compareAndSet(false, true)) return false;
        consumer.run();
        return true;
    }

    public boolean delivered() {
        return delivered.get();
    }
}
