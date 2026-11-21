package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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

    /**
     * Compiler-lowering target for a heap-resident generator frame. A frame
     * saves its own program counter and environment across pulls. It may
     * suspend inside a pull using OresScheduler.await(), without parking a
     * producer thread or running guest code from a Future completion callback.
     */
    @FunctionalInterface
    interface SchedulerFrame<T> {
        OresScheduler.Step<Step<T>> resume(OresScheduler.Resume input) throws Exception;

        /** Cancellation is signaled by the scheduler; cleanup runs on its guest lane. */
        default void cancellationRequested(OresScheduler.TaskCancelledException reason) { }

        /** Lowered source cleanup may await without losing its task ownership. */
        default boolean cleanupInProgress() { return false; }
    }

    /**
     * Staged bridge for compiler-lowered generator frames. Each pull becomes
     * exactly one root-scheduler task; its physical DONE is certified after
     * the last guest carrier exits. The host API is intentionally not wired
     * to this path until source yield/finally lowering is complete.
     */
    static final class SchedulerBacked<T> {
        private final OresScheduler root;
        private final HostGeneratorTurnPump.OwnerToken owner;
        private final SchedulerFrame<T> frame;
        private final AtomicBoolean terminal = new AtomicBoolean();
        // The pump serializes host admissions, but the heap frame must also
        // defend its mutable program counter against an accidental second
        // internal start before the first physical guest turn exits.
        private final AtomicBoolean physicalTurnInFlight = new AtomicBoolean();
        private final AtomicBoolean unprovenPhysicalExit = new AtomicBoolean();

        private SchedulerBacked(OresScheduler root,
                HostGeneratorTurnPump.OwnerToken owner, SchedulerFrame<T> frame) {
            this.root = Objects.requireNonNull(root, "root");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.frame = Objects.requireNonNull(frame, "frame");
        }

        HostGeneratorTurnPump.Start<Step<T>> nextTurn() {
            return () -> {
                if (unprovenPhysicalExit.get()) {
                    throw new IllegalStateException(
                            "generator frame is quarantined after unknown physical exit");
                }
                if (!physicalTurnInFlight.compareAndSet(false, true)) {
                    throw new IllegalStateException(
                            "generator frame already owns a physical guest turn");
                }
                try {
                    HostGeneratorTurnPump.Turn<Step<T>> turn =
                            root.startCertifiedGeneratorTurn(owner,
                                    new OresScheduler.CancellationAwareTask<Step<T>>() {
                                        @Override
                                        public boolean cleanupInProgress() {
                                            return frame.cleanupInProgress();
                                        }

                                        @Override
                                        public void cancellationRequested(
                                                OresScheduler.TaskCancelledException reason) {
                                            frame.cancellationRequested(reason);
                                        }

                                        @Override
                                        public OresScheduler.Step<Step<T>> resume(
                                                OresScheduler.Resume input) throws Exception {
                                            if (terminal.get()) {
                                                return OresScheduler.done(Step.doneStep());
                                            }
                                            try {
                                                return Objects.requireNonNull(frame.resume(input),
                                                        "generator frame returned no scheduler step");
                                            } catch (Exception | Error failure) {
                                                terminal.set(true);
                                                throw failure;
                                            }
                                        }
                                    });
                    turn.result().whenCompleteRuntime((step, failure) -> {
                        if (failure != null || step != null && step.done()) {
                            terminal.set(true);
                        }
                    });
                    turn.physicalExit().whenCompleteRuntime((ignored, failure) -> {
                        if (failure != null) {
                            terminal.set(true);
                            unprovenPhysicalExit.set(true);
                            // A failed receipt cannot release the frame lease.
                        } else {
                            physicalTurnInFlight.set(false);
                        }
                    });
                    return turn;
                } catch (RuntimeException | Error failure) {
                    // The starter may have launched guest work before throwing.
                    // Without its receipt, never reuse the heap continuation.
                    terminal.set(true);
                    unprovenPhysicalExit.set(true);
                    throw failure;
                }
            };
        }

        boolean isTerminal() { return terminal.get(); }
    }

    static <T> SchedulerBacked<T> schedulerBacked(
            OresScheduler root, HostGeneratorTurnPump.OwnerToken owner,
            SchedulerFrame<T> frame) {
        return new SchedulerBacked<>(root, owner, frame);
    }

    /**
     * Restricted, fail-closed source-level generator lowering, staged for
     * integration with the real evaluator's heap continuation machine.
     * It compiles a typechecked .ores function body, not a handwritten
     * Java producer. Only direct typed yield literals, yield-await of a
     * named Future, bare return, and literal-binding lexical finally are
     * admitted. All other constructs are rejected BEFORE guest dispatch.
     *
     * The ordinary interpreter and public host entrypoint remain unchanged.
     */
    static final class SourceLowering implements SchedulerFrame<Object> {
        private record YieldOperation(Object literal, String awaitedName) { }

        private final List<YieldOperation> operations;
        private final List<Ast.BindingStmt> finallyBindings;
        private final Map<String, Object> arguments;
        private final Map<String, Object> cleanupValues = new LinkedHashMap<>();
        private final AtomicInteger cleanupRuns = new AtomicInteger();
        private final AtomicBoolean finalized = new AtomicBoolean();
        private volatile boolean cancellationRequested;
        private volatile boolean waitingOnFuture;
        private int pc;

        private SourceLowering(List<YieldOperation> operations,
                List<Ast.BindingStmt> finallyBindings, Map<String, Object> arguments) {
            this.operations = List.copyOf(operations);
            this.finallyBindings = List.copyOf(finallyBindings);
            this.arguments = Map.copyOf(arguments);
        }

        static SourceLowering compile(String source, String function, Map<String, ?> args) {
            return compile(OresCompiler.parseAndTypeCheck(source), function, args);
        }

        static SourceLowering compile(Ast.Program program, String function, Map<String, ?> args) {
            Objects.requireNonNull(program, "program");
            Ast.FunctionDecl target = null;
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl found && found.name().equals(function)) {
                        if (target != null) throw new IllegalArgumentException(
                                "ambiguous generator function " + function);
                        target = found;
                    }
                }
            }
            if (target == null || !target.async() || !target.generator()
                    || target.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "source lowering requires a non-actor async generator function");
            }
            if (args.size() < target.parameters().size()) {
                throw new IllegalArgumentException("generator argument count mismatch");
            }
            Map<String, Object> bindings = new LinkedHashMap<>();
            for (Ast.Param param : target.parameters()) {
                if (!args.containsKey(param.name()) || args.get(param.name()) == null) {
                    throw new IllegalArgumentException(
                            "missing non-null generator argument " + param.name());
                }
                bindings.put(param.name(), args.get(param.name()));
            }
            // The restricted lowering can bind a test-controlled pending
            // result of an actual source-declared zero-argument async call.
            // Unlike an async callable parameter, this Future is NOT passed
            // across the language's ownership boundary as guest task data.
            // Every injected test dependency must name a typechecked,
            // non-generator async callable with no arguments.
            for (Map.Entry<String, ?> external : args.entrySet()) {
                if (bindings.containsKey(external.getKey())) continue;
                boolean declared = false;
                for (Ast.ModuleDecl module : program.modules()) {
                    for (Ast.Decl decl : module.declarations()) {
                        if (decl instanceof Ast.FunctionDecl async
                                && async.name().equals(external.getKey())
                                && async.async() && !async.generator()
                                && async.parameters().isEmpty()) declared = true;
                    }
                }
                if (!declared || !(external.getValue() instanceof OresFuture<?>)) {
                    throw new IllegalArgumentException(
                            "injected await dependency must be a declared zero-arg async function");
                }
                bindings.put(external.getKey(), external.getValue());
            }

            List<Ast.Stmt> body = new ArrayList<>();
            List<Ast.BindingStmt> cleanup = new ArrayList<>();
            boolean foundFinally = false;
            for (Ast.Stmt stmt : target.body()) {
                if (stmt instanceof Ast.TryStmt tried) {
                    if (foundFinally || !tried.catchBody().isEmpty()) {
                        throw new IllegalArgumentException(
                                "staged generator lowering requires one try/finally without catch");
                    }
                    foundFinally = true;
                    body.addAll(tried.body());
                    for (Ast.Stmt fin : tried.finallyBody()) {
                        if (!(fin instanceof Ast.BindingStmt binding)
                                || !(binding.initializer() instanceof Ast.LiteralExpr)) {
                            throw new IllegalArgumentException(
                                    "staged generator finally supports literal bindings only");
                        }
                        cleanup.add(binding);
                    }
                } else {
                    body.add(stmt);
                }
            }

            List<YieldOperation> operations = new ArrayList<>();
            boolean returned = false;
            for (Ast.Stmt stmt : body) {
                if (returned) throw new IllegalArgumentException(
                        "statements following generator return are unsupported");
                if (stmt instanceof Ast.ReturnStmt ret) {
                    if (ret.value() != null) throw new IllegalArgumentException(
                            "generator return cannot contain a value");
                    returned = true;
                } else if (stmt instanceof Ast.YieldStmt yielded && !yielded.delegated()) {
                    if (yielded.value() instanceof Ast.LiteralExpr literal) {
                        operations.add(new YieldOperation(literal.value(), null));
                    } else if (yielded.value() instanceof Ast.AwaitExpr awaited
                            && awaited.expression() instanceof Ast.CallExpr call
                            && call.callee() instanceof Ast.NameExpr named
                            && call.arguments().isEmpty()
                            && bindings.get(named.name()) instanceof OresFuture<?>) {
                        operations.add(new YieldOperation(null, named.name()));
                    } else {
                        throw new IllegalArgumentException(
                                "staged generator lowering does not support this yield expression");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "staged generator lowering refuses unsupported source statement");
                }
            }
            return new SourceLowering(operations, cleanup, bindings);
        }

        @Override
        public void cancellationRequested(OresScheduler.TaskCancelledException reason) {
            cancellationRequested = true;
        }

        @Override
        public OresScheduler.Step<Step<Object>> resume(OresScheduler.Resume input) {
            if (finalized.get()) return OresScheduler.done(Step.doneStep());
            if (cancellationRequested
                    || input.failure() instanceof OresScheduler.TaskCancelledException) {
                finishFinally();
                return OresScheduler.done(Step.doneStep());
            }
            if (waitingOnFuture) {
                if (input.initial()) throw new IllegalStateException(
                        "awaiting generator turn incorrectly restarted");
                waitingOnFuture = false;
                if (input.failure() != null) {
                    finishFinally();
                    if (input.failure() instanceof RuntimeException failure) throw failure;
                    throw new IllegalStateException("generator await failed", input.failure());
                }
                return OresScheduler.done(Step.yielded(input.value()));
            }
            if (!input.initial()) {
                throw new IllegalStateException("generator pull missing initial dispatch");
            }
            if (pc >= operations.size()) {
                finishFinally();
                return OresScheduler.done(Step.doneStep());
            }
            YieldOperation op = operations.get(pc++);
            if (op.awaitedName() != null) {
                waitingOnFuture = true;
                return OresScheduler.await((OresFuture<?>) arguments.get(op.awaitedName()));
            }
            return OresScheduler.done(Step.yielded(op.literal()));
        }

        private void finishFinally() {
            if (!finalized.compareAndSet(false, true)) return;
            cleanupRuns.incrementAndGet();
            for (Ast.BindingStmt binding : finallyBindings) {
                cleanupValues.put(binding.name(),
                        ((Ast.LiteralExpr) binding.initializer()).value());
            }
        }

        int cleanupRuns() { return cleanupRuns.get(); }
        Object cleanupBinding(String name) { return cleanupValues.get(name); }
        boolean awaitingSourceFuture() { return waitingOnFuture; }
    }

    public static <T> Generator<T> generator(AsyncRuntime runtime, Producer<T> producer) {
        return new Generator<>(new Engine<>(runtime, producer));
    }

    public static <T> AsyncGenerator<T> asyncGenerator(AsyncRuntime runtime, Producer<T> producer) {
        return new AsyncGenerator<>(runtime, new Engine<>(runtime, producer));
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
        private final Producer<T> producer;
        private final ArrayBlockingQueue<Object> resumes = new ArrayBlockingQueue<>(1);
        private final ArrayBlockingQueue<Event<T>> events = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<OresFuture<Void>> producerTask = new AtomicReference<>();
        private final Object launchLock = new Object();
        private volatile boolean done;
        private volatile Throwable backingFailure;

        private Engine(AsyncRuntime runtime, Producer<T> producer) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.producer = Objects.requireNonNull(producer, "producer");
        }

        private boolean isClosed() {
            return closed.get();
        }

        private synchronized Step<T> next() {
            if (done || closed.get()) { awaitProducerExit(); return Step.doneStep(); }
            throwIfBackingFailed();
            startIfNeeded();
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
                if (closed.get() || !started.compareAndSet(false, true)) return;
                OresFuture<Void> task = runtime.submit(() -> {
                    try {
                        takeResume();
                        if (!closed.get()) {
                            producer.run(this::emit);
                        }
                        publish(new DoneEvent<>());
                    } catch (CancellationException cancelled) {
                        if (!closed.get()) publish(new FailedEvent<>(cancelled));
                    } catch (Throwable failure) {
                        if (!closed.get()) publish(new FailedEvent<>(failure));
                    }
                    return null;
                });
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
            boolean first = closed.compareAndSet(false, true);
            if (first) {
                done = true;
                synchronized (launchLock) {
                    OresFuture<Void> task = producerTask.get();
                    if (task != null) task.cancel(true);
                }
                resumes.offer(RESUME);
                events.offer(new DoneEvent<>());
            }
            awaitProducerExit();
        }
    }
}
