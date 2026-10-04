package dev.oreslang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime lowering target for synchronous Oreslang generators.
 *
 * <p>A source-level {@code yield value} suspends a compiler-generated Frame and
 * returns one {@link Iteration} to the consumer. No host thread is parked and
 * this class has no actor-scheduler semantics: yielding a generator value does
 * not release an actor execution lease. If a generator is consumed from actor
 * code, it executes synchronously inside that actor turn until the next
 * generator yield/completion.</p>
 *
 * <p>The three type parameters mirror JavaScript's generator protocol:
 * yielded value ({@code Y}), final return value ({@code R}), and value sent
 * back into a suspended yield ({@code N}).</p>
 */
public final class OresGenerator<Y, R, N> implements Iterable<Y>, AutoCloseable {
    public enum ResumeKind {
        START,
        NEXT,
        THROW
    }

    /**
     * Input delivered to the compiler-generated frame when it resumes.
     *
     * <p>START has no sent value. NEXT may carry a value supplied by the
     * consumer. THROW carries a failure to be raised at the suspended yield
     * point so source try/catch/finally semantics can handle it.</p>
     */
    public record Resume<N>(ResumeKind kind, N value, Throwable failure) {
        public Resume {
            Objects.requireNonNull(kind, "kind");
            if (kind == ResumeKind.THROW) {
                Objects.requireNonNull(failure, "failure");
            } else if (failure != null) {
                throw new IllegalArgumentException(
                        "only THROW generator resumes may carry a failure");
            }
        }

        public static <N> Resume<N> start() {
            return new Resume<>(ResumeKind.START, null, null);
        }

        public static <N> Resume<N> next(N value) {
            return new Resume<>(ResumeKind.NEXT, value, null);
        }

        public static <N> Resume<N> throwing(Throwable failure) {
            return new Resume<>(ResumeKind.THROW, null, failure);
        }
    }

    public sealed interface Step<Y, R> permits Yielded, Complete { }

    public record Yielded<Y, R>(Y value) implements Step<Y, R> { }

    public record Complete<Y, R>(R value) implements Step<Y, R> { }

    /**
     * One observable generator protocol result.
     *
     * <p>When {@link #done()} is false, {@link #yieldedValue()} is valid. When
     * done is true, {@link #returnValue()} is valid.</p>
     */
    public static final class Iteration<Y, R> {
        private final boolean done;
        private final Y yieldedValue;
        private final R returnValue;

        private Iteration(boolean done, Y yieldedValue, R returnValue) {
            this.done = done;
            this.yieldedValue = yieldedValue;
            this.returnValue = returnValue;
        }

        public static <Y, R> Iteration<Y, R> yielded(Y value) {
            return new Iteration<>(false, value, null);
        }

        public static <Y, R> Iteration<Y, R> complete(R value) {
            return new Iteration<>(true, null, value);
        }

        public boolean done() {
            return done;
        }

        public Y yieldedValue() {
            if (done) {
                throw new IllegalStateException(
                        "completed generator iteration has no yielded value");
            }
            return yieldedValue;
        }

        public R returnValue() {
            if (!done) {
                throw new IllegalStateException(
                        "yielded generator iteration has no final return value");
            }
            return returnValue;
        }
    }

    /**
     * Heap/state-machine frame emitted by generator lowering.
     *
     * <p>The frame owns its program counter, live locals, loop/try/defer state,
     * and any source cleanup required when the generator is abandoned. close()
     * must be idempotent because runtime shutdown and consumer cancellation can
     * race with normal completion.</p>
     */
    @FunctionalInterface
    public interface Frame<Y, R, N> extends AutoCloseable {
        Step<Y, R> resume(Resume<N> resume) throws Exception;

        @Override
        default void close() throws Exception { }
    }

    private enum State {
        NEW,
        SUSPENDED,
        RUNNING,
        DONE,
        CLOSED,
        FAILED
    }

    private final Frame<Y, R, N> frame;
    private final AtomicBoolean frameClosed = new AtomicBoolean();
    private State state = State.NEW;
    private R completionValue;
    private Throwable failure;

    public OresGenerator(Frame<Y, R, N> frame) {
        this.frame = Objects.requireNonNull(frame, "frame");
    }

    public synchronized boolean isDone() {
        return state == State.DONE || state == State.CLOSED || state == State.FAILED;
    }

    public synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    public synchronized Iteration<Y, R> next() {
        return resume(state == State.NEW ? Resume.start() : Resume.next(null));
    }

    /**
     * Resume a suspended generator and make {@code value} the result of the
     * previous yield expression.
     *
     * <p>As in JavaScript, a value passed to the first next(value) cannot be
     * observed because there is no suspended yield yet, so the first call is
     * treated as START.</p>
     */
    public synchronized Iteration<Y, R> next(N value) {
        return resume(state == State.NEW ? Resume.start() : Resume.next(value));
    }

    /**
     * Raise a failure at the current suspended yield point.
     */
    public synchronized Iteration<Y, R> throwInto(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (state == State.NEW) {
            failClosed(throwable);
            throw propagate(throwable);
        }
        return resume(Resume.throwing(throwable));
    }

    public synchronized R completionValue() {
        if (state != State.DONE) {
            throw new IllegalStateException("generator has not completed normally");
        }
        return completionValue;
    }

    public synchronized Throwable failure() {
        if (state != State.FAILED) {
            throw new IllegalStateException("generator has not failed");
        }
        return failure;
    }

    private Iteration<Y, R> resume(Resume<N> input) {
        return switch (state) {
            case DONE -> Iteration.complete(completionValue);
            case CLOSED -> throw new IllegalStateException("generator is closed");
            case FAILED -> throw new IllegalStateException("generator previously failed", failure);
            case RUNNING -> throw new IllegalStateException("generator cannot be resumed reentrantly");
            case NEW, SUSPENDED -> resumeLive(input);
        };
    }

    private Iteration<Y, R> resumeLive(Resume<N> input) {
        state = State.RUNNING;
        final Step<Y, R> step;
        try {
            step = Objects.requireNonNull(
                    frame.resume(input),
                    "generator frame returned null step");
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            failClosed(fatal);
            throw fatal;
        } catch (Throwable thrown) {
            failClosed(thrown);
            throw propagate(thrown);
        }

        if (step instanceof Yielded<Y, R> yielded) {
            state = State.SUSPENDED;
            return Iteration.yielded(yielded.value());
        }

        @SuppressWarnings("unchecked")
        Complete<Y, R> complete = (Complete<Y, R>) step;
        completionValue = complete.value();
        state = State.DONE;
        closeFrameAfterNormalCompletion();
        return Iteration.complete(completionValue);
    }

    private void closeFrameAfterNormalCompletion() {
        try {
            closeFrameOnce();
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            state = State.FAILED;
            failure = fatal;
            throw fatal;
        } catch (Throwable closeFailure) {
            state = State.FAILED;
            failure = closeFailure;
            throw propagate(closeFailure);
        }
    }

    private void failClosed(Throwable thrown) {
        state = State.FAILED;
        failure = thrown;
        try {
            closeFrameOnce();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != thrown) thrown.addSuppressed(cleanupFailure);
        }
    }

    private void closeFrameOnce() throws Exception {
        if (frameClosed.compareAndSet(false, true)) frame.close();
    }

    private static RuntimeException propagate(Throwable thrown) {
        if (thrown instanceof RuntimeException runtime) return runtime;
        if (thrown instanceof Error error) throw error;
        return new IllegalStateException("generator frame failed", thrown);
    }

    /**
     * Deterministically abandon a suspended generator and unwind its lowered
     * cleanup/finally state.
     */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED || state == State.DONE) return;
        if (state == State.RUNNING) {
            throw new IllegalStateException("cannot close a generator while its frame is running");
        }
        try {
            closeFrameOnce();
            state = State.CLOSED;
        } catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            state = State.FAILED;
            failure = fatal;
            throw fatal;
        } catch (Throwable thrown) {
            state = State.FAILED;
            failure = thrown;
            throw propagate(thrown);
        }
    }

    /**
     * Iterator view consumes yielded values and intentionally discards the
     * generator's final return value.
     *
     * <p>This adapter is single-consumer. Mixing iterator() calls with direct
     * next()/throwInto() calls on the same generator is a logic error.</p>
     */
    @Override
    public Iterator<Y> iterator() {
        return new Iterator<>() {
            private Iteration<Y, R> buffered;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (exhausted) return false;
                if (buffered == null) {
                    buffered = OresGenerator.this.next();
                    if (buffered.done()) {
                        exhausted = true;
                        buffered = null;
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Y next() {
                if (!hasNext()) throw new NoSuchElementException();
                Y value = buffered.yieldedValue();
                buffered = null;
                return value;
            }
        };
    }
}
