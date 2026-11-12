package dev.oreslang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime substrate for Oreslang generator activations.
 *
 * <p>A generator owns exactly one suspended activation. Pulls are serialized:
 * the consumer grants one resume permit, the producer runs until the next
 * yield/completion/failure, and then control returns to the consumer. The
 * producer uses {@link AsyncRuntime}'s context-owned virtual carrier so a
 * suspended generator does not pin an OS thread and is cancelled with its
 * OresContext.</p>
 *
 * <p>This is an interpreter implementation detail. A native/AOT lowering may
 * represent the same contract as an explicit program-counter/state-machine
 * frame without changing language semantics.</p>
 */
public final class GeneratorRuntime {
    private GeneratorRuntime() { }

    @FunctionalInterface
    public interface Emitter<T> {
        void emit(T value);
    }

    @FunctionalInterface
    public interface Producer<T> {
        void run(Emitter<T> emitter) throws Exception;
    }

    public record Step<T>(boolean done, T value) {
        public static <T> Step<T> yielded(T value) { return new Step<>(false, value); }
        public static <T> Step<T> doneStep() { return new Step<>(true, null); }
    }

    public static <T> Generator<T> generator(AsyncRuntime runtime, Producer<T> producer) {
        return new Generator<>(new Engine<>(runtime, producer));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(AsyncRuntime runtime, Producer<T> producer) {
        return new AsyncGenerator<>(runtime, new Engine<>(runtime, producer));
    }

    public static final class Generator<T> implements Iterable<T>, AutoCloseable {
        private final Engine<T> engine;
        private final AtomicBoolean pullInFlight = new AtomicBoolean();
        private final Iterator<T> iteratorView;

        private Generator(Engine<T> engine) {
            this.engine = engine;
            this.iteratorView = new Iterator<>() {
                private Step<T> buffered;

                @Override
                public synchronized boolean hasNext() {
                    if (Generator.this.engine.isClosed()) {
                        buffered = null;
                        return false;
                    }
                    if (buffered == null) buffered = nextStep();
                    if (Generator.this.engine.isClosed()) {
                        buffered = null;
                        return false;
                    }
                    return !buffered.done();
                }

                @Override
                public synchronized T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    T value = buffered.value();
                    buffered = null;
                    return value;
                }
            };
        }

        public Step<T> nextStep() {
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "synchronous generator iteration cannot park an actor carrier; "
                                + "use an async generator and 'for await'");
            }
            if (!pullInFlight.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "concurrent Iterator.next() is not permitted; finish the outstanding pull first");
            }
            try {
                return engine.next();
            } finally {
                pullInFlight.set(false);
            }
        }

        @Override
        public Iterator<T> iterator() {
            return iteratorView;
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    public static final class AsyncGenerator<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Engine<T> engine;
        // Bound pull admission at one outstanding request. The Engine also
        // serializes execution, but admitting an unbounded number of runtime
        // tasks that merely wait on its monitor would turn concurrent next()
        // calls into a resource-amplification path.
        private final AtomicBoolean pullInFlight = new AtomicBoolean();
        private OresFuture<Void> closeFuture;

        private AsyncGenerator(AsyncRuntime runtime, Engine<T> engine) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.engine = engine;
        }

        /**
         * One asynchronous pull. Exactly one pull may be outstanding for an
         * activation. An overlapping pull returns a failed OresFuture instead
         * of admitting another runtime task that would wait behind the first.
         */
        public OresFuture<Step<T>> nextStep() {
            if (!pullInFlight.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "concurrent AsyncIterator.next() is not permitted; await the outstanding pull first"));
            }

            // Only a *settled* terminal generator can return an immediate
            // Future. A terminal event can precede the physical guest exit;
            // never park the caller (possibly an actor carrier) in this API.
            if (engine.isTerminalReady()) {
                // A producer admission denial remains an exceptional async
                // result across repeated pulls; never launder it into DONE.
                Throwable rejected = engine.terminalFailure();
                pullInFlight.set(false);
                return rejected == null
                        ? OresFuture.completed(Step.doneStep())
                        : OresFuture.failed(rejected);
            }

            // An async pull always returns an OresFuture, including when
            // scheduler admission itself rejects before any guest work starts.
            try {
                // Future cancellation settles before AsyncRuntime's backing task
                // necessarily leaves engine.next(). Arbitrate task entry against
                // early completion so cancellation cannot reopen pull admission
                // while the cancelled consumer still owns the activation.
                AtomicInteger pullState = new AtomicInteger(); // 0=pending, 1=inside engine.next, 2=released
                OresFuture<Step<T>> pull = runtime.submit(() -> {
                    if (!pullState.compareAndSet(0, 1)) {
                        throw new CancellationException(
                                "async generator pull cancelled before engine admission");
                    }
                    try {
                        return engine.next();
                    } finally {
                        pullState.set(2);
                        pullInFlight.set(false);
                    }
                });
                pull.whenCompleteRuntime((value, failure) -> {
                    // Admission/turn failure or cancellation before the task
                    // entered engine.next() has no physical pull to unwind.
                    // If engine.next() is already running, its finally owns the
                    // release and this callback must leave the gate closed.
                    if (pullState.compareAndSet(0, 2)) {
                        pullInFlight.set(false);
                    }
                });
                return pull;
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                pullInFlight.set(false);
                return OresFuture.failed(rejected);
            } catch (RuntimeException | Error failure) {
                pullInFlight.set(false);
                throw failure;
            }
        }

        /**
         * Source-level async close. Close is signalled synchronously without
         * parking the caller, then the returned receipt settles only after the
         * producer's physical guest turn has retired. No second runtime task is
         * needed, so runtime shutdown cannot cancel the only cleanup waiter.
         */
        public synchronized OresFuture<Void> closeAsync() {
            if (closeFuture == null) closeFuture = engine.closeAsyncReceipt();
            return closeFuture;
        }

        @Override
        public void close() {
            if (ActorRuntime.inActorExecution() && !engine.isTerminalReady()) {
                throw new IllegalStateException(
                        "synchronous AsyncIterator.close() would park an actor carrier; "
                                + "use/await the asynchronous close operation");
            }
            engine.close();
        }
    }

    private sealed interface Event<T> permits YieldEvent, DoneEvent, FailedEvent { }
    private record YieldEvent<T>(T value) implements Event<T> { }
    private record DoneEvent<T>() implements Event<T> { }
    private record FailedEvent<T>(Throwable failure) implements Event<T> { }

    private static final Object RESUME = new Object();

    private static final class Engine<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Producer<T> producer;
        private final ArrayBlockingQueue<Object> resumes = new ArrayBlockingQueue<>(1);
        private final ArrayBlockingQueue<Event<T>> events = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<OresFuture<Void>> producerTask = new AtomicReference<>();
        // A non-cancellation failure raised while unwinding an explicit close
        // must not disappear merely because the backing task Future was
        // already marked cancelled. Keep it as a sticky close result.
        private final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        private final Object launchLock = new Object();
        private volatile boolean done;
        // True only after the producer's actual guest turn has exited and
        // the terminal observation may be published without any blocking.
        private volatile boolean terminalReady;
        private volatile Throwable backingFailure;

        private Engine(AsyncRuntime runtime, Producer<T> producer) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.producer = Objects.requireNonNull(producer, "producer");
        }

        private boolean isClosed() {
            return closed.get();
        }

        private boolean isTerminalReady() {
            return terminalReady;
        }

        private Throwable terminalFailure() {
            return closed.get() ? null : backingFailure;
        }

        private synchronized Step<T> next() {
            // Explicit close stops iteration; otherwise preserve the
            // producer's terminal failure across every later pull.
            if (closed.get()) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            throwIfBackingFailed();
            if (done) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            startIfNeeded();
            throwIfBackingFailed();
            putResume();
            Event<T> event = takeEvent();
            if (closed.get()) {
                awaitProducerExit();
                terminalReady = true;
                return Step.doneStep();
            }
            throwIfBackingFailed();
            if (event instanceof YieldEvent<?> yielded) {
                @SuppressWarnings("unchecked")
                T value = (T) yielded.value();
                return Step.yielded(value);
            }
            done = true;
            awaitProducerExit();
            if (event instanceof FailedEvent<?> failed) {
                // The published failure is an immutable terminal observation,
                // not a one-shot event to forget after its first consumer.
                backingFailure = failed.failure();
                throwIfBackingFailed();
            }
            terminalReady = true;
            return Step.doneStep();
        }

        private void startIfNeeded() {
            synchronized (launchLock) {
                if (closed.get() || !started.compareAndSet(false, true)) return;
                OresFuture<Void> task;
                try {
                    task = runtime.submit(() -> {
                        try {
                            takeResume();
                            if (!closed.get()) {
                                producer.run(this::emit);
                            }
                            publish(new DoneEvent<>());
                        } catch (CancellationException cancelled) {
                            if (!closed.get()) publish(new FailedEvent<>(cancelled));
                        } catch (InterruptedException interrupted) {
                            // Closing an active generator interrupts its producer.
                            // That is a requested shutdown, not a failed finally
                            // block. Preserve an unsolicited interruption as a
                            // sticky terminal error when close was NOT requested.
                            // Genuine cleanup failures still reach the Throwable
                            // branch below and remain visible on close receipts.
                            try {
                                if (!closed.get()) publish(new FailedEvent<>(interrupted));
                            } finally {
                                Thread.currentThread().interrupt();
                            }
                        } catch (Throwable failure) {
                            if (!closed.get()) publish(new FailedEvent<>(failure));
                            else closeFailure.compareAndSet(null, failure);
                        }
                        return null;
                    });
                } catch (RuntimeException | Error rejection) {
                    // The producer never started, so no event can arrive.
                    // Persist the denial rather than leaving a started
                    // activation waiting indefinitely on an empty queue.
                    backingFailure = rejection;
                    return;
                }
                producerTask.set(task);
                // Admission/context entry can fail before the producer body runs.
                // Its catch block then never publishes an event. Deliver that
                // backing failure without blocking the runtime completion thread.
                task.whenCompleteRuntime((value, failure) -> {
                    if (failure != null && !closed.get()) {
                        backingFailure = OresFuture.unwrap(failure);
                        events.offer(new FailedEvent<>(backingFailure));
                    }
                });
                if (closed.get()) task.cancel(true);
            }
        }

        private void awaitProducerExit() {
            OresFuture<Void> task;
            synchronized (launchLock) { task = producerTask.get(); }
            if (task != null) runtime.awaitTaskExit(task);
        }

        private void throwIfBackingFailed() {
            Throwable failure = backingFailure;
            if (failure == null) return;
            done = true;
            awaitProducerExit();
            terminalReady = true;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }

        private void emit(T value) {
            if (closed.get()) throw new CancellationException("generator is closed");
            publish(new YieldEvent<>(value));
            takeResume();
            if (closed.get()) throw new CancellationException("generator is closed");
        }

        private void publish(Event<T> event) {
            try {
                events.put(event);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void putResume() {
            try {
                resumes.put(RESUME);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void takeResume() {
            try {
                resumes.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator producer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private Event<T> takeEvent() {
            try {
                return events.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("generator consumer interrupted");
                cancelled.initCause(interrupted);
                throw cancelled;
            }
        }

        private void requestClose() {
            boolean first = closed.compareAndSet(false, true);
            if (!first) return;
            done = true;
            synchronized (launchLock) {
                OresFuture<Void> task = producerTask.get();
                if (task != null) task.cancel(true);
            }
            resumes.offer(RESUME);
            events.offer(new DoneEvent<>());
        }

        private void finishCloseAfterExit() {
            terminalReady = true;
            Throwable cleanupFailure = closeFailure.get();
            if (cleanupFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cleanupFailure instanceof Error error) {
                throw error;
            }
            if (cleanupFailure != null) {
                throw new RuntimeException(cleanupFailure);
            }
        }

        private OresFuture<Void> closeAsyncReceipt() {
            requestClose();
            OresFuture<Void> task;
            synchronized (launchLock) { task = producerTask.get(); }

            OresFuture<Void> receipt = new OresFuture<>(() -> false, () -> { });
            if (task == null) {
                try {
                    finishCloseAfterExit();
                    receipt.completeFromRuntime(null);
                } catch (Throwable failure) {
                    receipt.failFromRuntime(failure);
                }
                return receipt;
            }

            runtime.taskExitReceipt(task).whenCompleteRuntime((value, failure) -> {
                if (failure != null) {
                    receipt.failFromRuntime(OresFuture.unwrap(failure));
                    return;
                }
                try {
                    finishCloseAfterExit();
                    receipt.completeFromRuntime(null);
                } catch (Throwable cleanupFailure) {
                    receipt.failFromRuntime(cleanupFailure);
                }
            });
            return receipt;
        }

        @Override
        public void close() {
            requestClose();
            awaitProducerExit();
            finishCloseAfterExit();
        }
    }
}
