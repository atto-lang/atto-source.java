package dev.oreslang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @FunctionalInterface
    public interface StructuredProducer<T> {
        void run(
                Emitter<T> emitter,
                TaskScope.Registration scopeRegistration)
                throws Exception;
    }

    public record Step<T>(boolean done, T value) {
        public static <T> Step<T> yielded(T value) { return new Step<>(false, value); }
        public static <T> Step<T> doneStep() { return new Step<>(true, null); }
    }

    public static <T> Generator<T> generator(
            AsyncRuntime runtime,
            Producer<T> producer) {
        Objects.requireNonNull(producer, "producer");
        return new Generator<>(
                new Engine<>(
                        runtime,
                        (emitter, ignored) -> producer.run(emitter),
                        null,
                        null));
    }

    public static <T> Generator<T> generator(
            AsyncRuntime runtime,
            StructuredProducer<T> producer,
            TaskScope scope,
            TaskScope.Registration scopeParent) {
        return new Generator<>(
                new Engine<>(runtime, producer, scope, scopeParent));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(
            AsyncRuntime runtime,
            Producer<T> producer) {
        Objects.requireNonNull(producer, "producer");
        return new AsyncGenerator<>(
                runtime,
                new Engine<>(
                        runtime,
                        (emitter, ignored) -> producer.run(emitter),
                        null,
                        null));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(
            AsyncRuntime runtime,
            StructuredProducer<T> producer,
            TaskScope scope,
            TaskScope.Registration scopeParent) {
        return new AsyncGenerator<>(
                runtime,
                new Engine<>(runtime, producer, scope, scopeParent));
    }

    public static final class Generator<T> implements Iterable<T>, AutoCloseable {
        private final Engine<T> engine;

        private Generator(Engine<T> engine) {
            this.engine = engine;
        }

        public Step<T> nextStep() {
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "synchronous generator iteration cannot park an actor carrier; "
                                + "use an async generator and 'for await'");
            }
            return engine.next();
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<>() {
                private Step<T> buffered;

                @Override
                public boolean hasNext() {
                    if (buffered == null) buffered = nextStep();
                    return !buffered.done();
                }

                @Override
                public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    T value = buffered.value();
                    buffered = null;
                    return value;
                }
            };
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    public static final class AsyncGenerator<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Engine<T> engine;

        private AsyncGenerator(AsyncRuntime runtime, Engine<T> engine) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.engine = engine;
        }

        /**
         * One asynchronous pull. Calls are serialized by the activation even
         * if callers issue more than one pull before an earlier pull completes.
         */
        public OresFuture<Step<T>> nextStep() {
            if (engine.isClosed()) {
                engine.awaitProducerExit();
                return OresFuture.completed(Step.doneStep());
            }
            return runtime.submit(engine::next);
        }

        @Override
        public void close() {
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
        private final StructuredProducer<T> producer;
        private final TaskScope scope;
        private final TaskScope.Registration scopeParent;
        private final ArrayBlockingQueue<Object> resumes = new ArrayBlockingQueue<>(1);
        private final ArrayBlockingQueue<Event<T>> events = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<OresFuture<Void>> producerTask = new AtomicReference<>();
        private final Object launchLock = new Object();
        private volatile boolean done;
        private volatile Throwable backingFailure;

        private Engine(
                AsyncRuntime runtime,
                StructuredProducer<T> producer,
                TaskScope scope,
                TaskScope.Registration scopeParent) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.producer = Objects.requireNonNull(producer, "producer");
            this.scope = scope;
            this.scopeParent = scopeParent;
        }

        private boolean isClosed() {
            return closed.get();
        }

        private synchronized Step<T> next() {
            if (done || closed.get()) { awaitProducerExit(); return Step.doneStep(); }
            throwIfBackingFailed();
            startIfNeeded();
            if (closed.get()) {
                awaitProducerExit();
                return Step.doneStep();
            }
            throwIfBackingFailed();
            putResume();
            Event<T> event = takeEvent();
            if (closed.get()) { awaitProducerExit(); return Step.doneStep(); }
            throwIfBackingFailed();
            if (event instanceof YieldEvent<?> yielded) {
                @SuppressWarnings("unchecked")
                T value = (T) yielded.value();
                return Step.yielded(value);
            }
            done = true;
            awaitProducerExit();
            if (event instanceof FailedEvent<?> failed) {
                Throwable failure = failed.failure();
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (failure instanceof Error error) throw error;
                throw new RuntimeException(failure);
            }
            return Step.doneStep();
        }

        private void startIfNeeded() {
            synchronized (launchLock) {
                if (closed.get() || started.get()) return;

                TaskScope.Registration registration = null;
                if (scope != null) {
                    registration = scope.reserve(scopeParent);
                    registration.onScopeClose(this::requestScopeClose);
                    if (closed.get()) {
                        registration.retireUnstarted();
                        return;
                    }
                }

                if (!started.compareAndSet(false, true)) {
                    if (registration != null) registration.retireUnstarted();
                    return;
                }

                final TaskScope.Registration structured = registration;
                final OresFuture<Void> task;
                try {
                    task = structured == null
                            ? runtime.submit(() -> runProducer(null))
                            : runtime.submitStructured(
                                    () -> runProducer(structured),
                                    terminal -> {
                                        if (closed.get() && terminal.isCancelled()) {
                                            structured.terminalReadyAsSuccessfulClose(terminal);
                                        } else {
                                            structured.terminalReady(terminal);
                                        }
                                    },
                                    structured::quiesced);
                } catch (RuntimeException | Error admissionFailure) {
                    if (structured != null) {
                        structured.failedAdmission(admissionFailure);
                    }
                    throw admissionFailure;
                }

                producerTask.set(task);
                if (structured != null) {
                    try {
                        structured.attach(task);
                    } catch (RuntimeException | Error attachFailure) {
                        task.cancel(false);
                        throw attachFailure;
                    }
                }

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

        private Void runProducer(TaskScope.Registration registration)
                throws Exception {
            if (registration != null) {
                registration.beganExecution();
            }
            try {
                takeResume();
                if (!closed.get()) {
                    producer.run(this::emit, registration);
                }
                // Scope/user close may already have published the synthetic
                // terminal event that releases a concurrent pull. Never block
                // trying to publish a second terminal event into the one-slot
                // queue after close.
                if (!closed.get()) publish(new DoneEvent<>());
                return null;
            } catch (CancellationException cancelled) {
                if (!closed.get()) {
                    publish(new FailedEvent<>(cancelled));
                    if (registration != null) throw cancelled;
                }
                return null;
            } catch (RuntimeException failure) {
                if (!closed.get()) publish(new FailedEvent<>(failure));
                if (registration != null) throw failure;
                return null;
            } catch (Exception failure) {
                if (!closed.get()) publish(new FailedEvent<>(failure));
                if (registration != null) throw failure;
                return null;
            } catch (Error failure) {
                if (!closed.get()) publish(new FailedEvent<>(failure));
                throw failure;
            }
        }

        /**
         * Lexical scope close is a graceful producer stop request. It never
         * waits on the caller thread; normal TaskScope drain observes producer
         * terminal authority and physical AsyncRuntime retirement separately.
         * Exceptional scope cancellation may subsequently revoke the producer
         * Future if graceful shutdown has not completed yet.
         */
        private void requestScopeClose() {
            if (!closed.compareAndSet(false, true)) return;
            done = true;
            resumes.offer(RESUME);
            events.offer(new DoneEvent<>());
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

        @Override
        public void close() {
            requestScopeClose();
            // Explicit close is stronger than the lexical scope's graceful
            // close request. It may arrive after that request and must still
            // be able to interrupt a producer stuck in host/runtime work.
            synchronized (launchLock) {
                OresFuture<Void> task = producerTask.get();
                if (task != null) task.cancel(true);
            }
            awaitProducerExit();
        }
    }
}
