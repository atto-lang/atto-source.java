package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-owned suspended generator body.
 *
 * <p>This is the compatibility execution bridge for the tree-walking
 * interpreter. The producer body runs on an Ores async virtual carrier and
 * parks only that virtual carrier at a yield point. The public language
 * contract is iterator/state-machine based; AOT/JIT lowering can later replace
 * this bridge with heap-resident continuation frames without changing the
 * source or protocol types.</p>
 *
 * <p>An OresGenerator is deliberately single-consumer and non-shareable.
 * Exactly one {@code next()} request may be in flight. Closing a generator
 * cancels the producer and releases a producer currently suspended at
 * {@code yield}.</p>
 */
public final class OresGenerator<T> implements AutoCloseable {
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    public record Step<T>(T value, boolean done) {
        public static <T> Step<T> yielded(T value) {
            return new Step<>(value, false);
        }

        public static <T> Step<T> doneStep() {
            return new Step<>(null, true);
        }
    }

    private sealed interface Signal<T> permits Yielded, Finished, Failed { }
    private record Yielded<T>(T value) implements Signal<T> { }
    private record Finished<T>() implements Signal<T> { }
    private record Failed<T>(Throwable failure) implements Signal<T> { }

    private static final ThreadLocal<OresGenerator<?>> CURRENT = new ThreadLocal<>();

    private final AsyncRuntime runtime;
    private final Body body;
    private final ArrayBlockingQueue<Signal<T>> signals = new ArrayBlockingQueue<>(1);
    private final Semaphore resumePermit = new Semaphore(0);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean suspendedAtYield = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean nextInFlight = new AtomicBoolean();
    private final AtomicReference<OresFuture<Void>> producer = new AtomicReference<>();

    public OresGenerator(AsyncRuntime runtime, Body body) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.body = Objects.requireNonNull(body, "body");
    }

    /**
     * Advance to the next yield point.
     *
     * <p>This method is intentionally blocking at the Java implementation
     * boundary. AsyncIterator.next() schedules it through AsyncRuntime and
     * exposes an OresFuture to guest code.</p>
     */
    public Step<T> next() {
        if (!nextInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "generator next() is single-consumer; another next request is already in flight");
        }
        try {
            if (closed.get()) return Step.doneStep();
            if (completed.get() && signals.isEmpty()) return Step.doneStep();

            if (started.compareAndSet(false, true)) {
                startProducer();
            } else if (suspendedAtYield.compareAndSet(true, false)) {
                resumePermit.release();
            }

            Signal<T> signal = takeSignal();
            if (signal instanceof Yielded<T> yielded) {
                return Step.yielded(yielded.value());
            }
            if (signal instanceof Finished<T>) {
                completed.set(true);
                return Step.doneStep();
            }
            if (signal instanceof Failed<T> failed) {
                completed.set(true);
                return rethrow(failed.failure());
            }
            throw new IllegalStateException("unknown generator signal");
        } finally {
            nextInFlight.set(false);
        }
    }

    private void startProducer() {
        OresFuture<Void> future = runtime.submit(() -> {
            runProducer();
            return null;
        });
        producer.set(future);
        if (closed.get()) future.cancel(true);
    }

    private void runProducer() {
        if (closed.get()) {
            publishFinished();
            return;
        }

        OresGenerator<?> previous = CURRENT.get();
        if (previous != null) {
            publishFailure(new IllegalStateException(
                    "generator producer cannot begin while another generator owns this carrier"));
            return;
        }

        CURRENT.set(this);
        try {
            body.run();
            publishFinished();
        } catch (CancellationException cancelled) {
            if (!closed.get()) publishFailure(cancelled);
            else publishFinished();
        } catch (Throwable failure) {
            if (!closed.get()) publishFailure(failure);
            else publishFinished();
        } finally {
            completed.set(true);
            CURRENT.remove();
        }
    }

    /**
     * Called by the interpreter when it executes an Oreslang yield statement.
     */
    @SuppressWarnings("unchecked")
    public static void yieldCurrent(Object value) {
        OresGenerator<?> current = CURRENT.get();
        if (current == null) {
            throw new IllegalStateException(
                    "yield executed without an active generator continuation");
        }
        ((OresGenerator<Object>) current).yieldValue(value);
    }

    private void yieldValue(T value) {
        if (closed.get()) throw new CancellationException("generator is closed");
        suspendedAtYield.set(true);
        putSignal(new Yielded<>(value));

        try {
            resumePermit.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled =
                    new CancellationException("generator continuation interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        }

        if (closed.get()) throw new CancellationException("generator is closed");
    }

    private Signal<T> takeSignal() {
        try {
            return signals.take();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled =
                    new CancellationException("generator next() interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        }
    }

    private void putSignal(Signal<T> signal) {
        try {
            signals.put(signal);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled =
                    new CancellationException("generator signal interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        }
    }

    private void publishFinished() {
        // Normal completion occurs after a consumer resumed the previous yield,
        // so the one-slot queue is empty. close() may race and pre-populate the
        // queue with Finished; in that case another terminal signal is needless.
        signals.offer(new Finished<>());
    }

    private void publishFailure(Throwable failure) {
        signals.offer(new Failed<>(Objects.requireNonNull(failure, "failure")));
    }

    private static <T> Step<T> rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new RuntimeException(failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        if (suspendedAtYield.compareAndSet(true, false)) {
            resumePermit.release();
        }

        OresFuture<Void> future = producer.get();
        if (future != null) future.cancel(true);

        // Wake a consumer that may currently be waiting for the next signal.
        signals.offer(new Finished<>());
    }

    public boolean isClosed() {
        return closed.get();
    }

    public boolean isDone() {
        return completed.get() || closed.get();
    }
}
