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
        // Even synchronous generator construction retains a lazy Producer
        // closure that could be pulled outside this turn. Actor-private
        // values cannot escape through a root-owned activation.
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException(
                    "ACTOR_GENERATOR_UNSUPPORTED: sync gen* requires actor-owned "
                    + "activation/receiver lifetime; generic AsyncRuntime cannot retain actor authority");
        }
        return new Generator<>(new Engine<>(runtime, producer));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(AsyncRuntime runtime, Producer<T> producer) {
        // The generic AsyncRuntime owns process/root virtual carriers. It
        // does not carry an actor-incarnation execution lease or guarantee
        // that await/yield/cleanup resumes on that actor's kind-specific pool.
        // Reject construction during an actor turn rather than let a captured
        // actor-private producer execute outside its owner's scheduler.
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException(
                    "ACTOR_GENERATOR_UNSUPPORTED: async gen* requires actor-owned "
                    + "continuation/pool admission; generic AsyncRuntime cannot retain actor authority");
        }
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
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "ACTOR_GENERATOR_UNSUPPORTED: synchronous generator close "
                        + "requires root-owner cleanup; an actor cannot park its carrier");
            }
            engine.close();
        }
    }

    public static final class AsyncGenerator<T> implements AutoCloseable {
        private final AsyncRuntime runtime;
        private final Engine<T> engine;
        // A pull reservation is not reusable until BOTH the engine has left
        // next() and the returned Future is settled. In particular, a custom
        // TurnExecutor may perform host/context cleanup between those events.
        // Cancellation may settle a Future while engine.next() is still active.
        private final AtomicReference<PullAdmission<T>> activePull = new AtomicReference<>();
        private OresFuture<Void> closeFuture;

        private static final class PullAdmission<T> {
            // 0 = before engine entry, 1 = executing, 2 = left / denied entry.
            private final AtomicInteger state = new AtomicInteger();
            private volatile OresFuture<Step<T>> result;

            private boolean retired() {
                OresFuture<Step<T>> observed = result;
                return state.get() == 2 && observed != null && observed.isDone();
            }
        }

        private AsyncGenerator(AsyncRuntime runtime, Engine<T> engine) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.engine = engine;
        }

        /**
         * Admit exactly one unsettled asynchronous pull per activation. A new
         * pull may proceed only after the previous Future has settled AND its
         * engine step has exited (cancellation can settle first).
         */
        public OresFuture<Step<T>> nextStep() {
            // A root-owned activation can be passed to actor code. Even an
            // otherwise Sendable value may not grant that actor a generic
            // root-task execution capability. Preserve the Future return ABI.
            if (ActorRuntime.inActorExecution()) {
                return OresFuture.failed(new IllegalStateException(
                        "ACTOR_GENERATOR_UNSUPPORTED: actor pull requires "
                        + "actor-owned continuation/pool admission"));
            }
            PullAdmission<T> ticket = new PullAdmission<>();
            for (;;) {
                PullAdmission<T> previous = activePull.get();
                if (previous != null && !previous.retired()) {
                    return OresFuture.failed(new IllegalStateException(
                            "concurrent AsyncIterator.next() is not permitted; await the outstanding pull first"));
                }
                if (activePull.compareAndSet(previous, ticket)) break;
            }

            // Terminal pulls return an already-settled Future. A prior pull
            // still has to retire before reaching this shortcut.
            if (engine.isTerminalReady()) {
                Throwable rejected = engine.terminalFailure();
                OresFuture<Step<T>> terminal = rejected == null
                        ? OresFuture.completed(Step.doneStep())
                        : OresFuture.failed(rejected);
                ticket.result = terminal;
                ticket.state.set(2);
                activePull.compareAndSet(ticket, null);
                return terminal;
            }

            try {
                OresFuture<Step<T>> pull = runtime.submit(() -> {
                    if (!ticket.state.compareAndSet(0, 1)) {
                        throw new CancellationException(
                                "async generator pull cancelled before engine admission");
                    }
                    try {
                        return engine.next();
                    } finally {
                        ticket.state.set(2);
                        if (ticket.retired()) activePull.compareAndSet(ticket, null);
                    }
                });
                ticket.result = pull;
                pull.whenCompleteRuntime((value, failure) -> {
                    // If completion/cancellation arrived before engine entry,
                    // fence the entry out; otherwise engine.next()'s finally
                    // must retire before the permit can be reused.
                    ticket.state.compareAndSet(0, 2);
                    if (ticket.retired()) activePull.compareAndSet(ticket, null);
                });
                // The task can finish before the receipt is installed. Do
                // not leave a completed admission retained in that race.
                if (ticket.retired()) activePull.compareAndSet(ticket, null);
                return pull;
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                ticket.state.set(2);
                activePull.compareAndSet(ticket, null);
                return OresFuture.failed(rejected);
            } catch (RuntimeException | Error failure) {
                ticket.state.set(2);
                activePull.compareAndSet(ticket, null);
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
            // A root-owned generator is not an actor capability. Even a
            // non-parking close would cancel its producer from a foreign
            // actor turn and could execute finalizers outside the owner pool.
            if (ActorRuntime.inActorExecution()) {
                return OresFuture.failed(new IllegalStateException(
                        "ACTOR_GENERATOR_UNSUPPORTED: async generator close "
                        + "requires its owning execution domain"));
            }
            if (closeFuture == null) closeFuture = engine.closeAsyncReceipt();
            return closeFuture;
        }

        @Override
        public void close() {
            if (ActorRuntime.inActorExecution()) {
                throw new IllegalStateException(
                        "ACTOR_GENERATOR_UNSUPPORTED: synchronous async generator "
                        + "close requires its owning execution domain");
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
                        } catch (Throwable failure) {
                            if (!closed.get()) {
                                publish(new FailedEvent<>(failure));
                            } else if (!(failure instanceof InterruptedException)) {
                                // close() cancels the producer task with
                                // interruption. The interrupt used to stop
                                // its blocked body is an expected cancellation,
                                // not a failed defer/finally. Preserve all
                                // other cleanup errors, including exceptions
                                // raised while unwinding after cancellation.
                                closeFailure.compareAndSet(null, failure);
                            }
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
