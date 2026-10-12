package dev.oreslang.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Staged Java-host pull facade for a parsed, typechecked, scheduler-lowered
 * Oreslang generator. No virtual-thread producer or direct guest Future
 * completion can authorize its next turn.
 *
 * This is an internal integration boundary, NOT yet a public Polyglot Value
 * entrypoint. A real Truffle embedder must provide its context-owned root
 * guest entry predicate and must drive disposal through the owning context.
 */
public final class HostGeneratorSession implements AutoCloseable {
    private final OresScheduler root;
    private final HostGeneratorTurnPump pump;
    private final GeneratorRuntime.SchedulerBacked<Object> frame;
    private final GeneratorRuntime.SourceLowering lowered;
    private final AtomicBoolean sealed = new AtomicBoolean();

    private HostGeneratorSession(OresScheduler root, HostGeneratorTurnPump pump,
                                 GeneratorRuntime.SchedulerBacked<Object> frame,
                                 GeneratorRuntime.SourceLowering lowered) {
        this.root = root;
        this.pump = pump;
        this.frame = frame;
        this.lowered = lowered;
    }

    static HostGeneratorSession compile(
            OresScheduler root, BooleanSupplier physicallyEnteredRoot,
            String source, String function, Map<String, ?> arguments) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(physicallyEnteredRoot, "physicallyEnteredRoot");
        // Compile and reject unsupported/unsafe source BEFORE any physical
        // guest task, pump admission, or host-visible handle is created.
        var lowered = GeneratorRuntime.SourceLowering.compile(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(function, "function"),
                Objects.requireNonNull(arguments, "arguments"));
        AtomicReference<HostGeneratorTurnPump> registered = new AtomicReference<>();
        Runnable signalRoot = () -> {
            HostGeneratorTurnPump owned = registered.get();
            if (owned == null) {
                throw new IllegalStateException("generator pump not registered");
            }
            // This only schedules owner work: never call dispatchOne inline
            // from a host thread or execute guest callbacks while holding
            // the pump's monitor. In a Polyglot context the root executor
            // must enter the real context before this predicate passes.
            OresFuture<Void> dispatched = root.startSync(() -> {
                owned.dispatchOne();
                return null;
            });
            // If guest-entry authorization itself fails, do not strand
            // a host request whose scheduler dispatch future is private.
            dispatched.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) owned.seal();
            });
        };
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), physicallyEnteredRoot, signalRoot, 256, root);
        registered.set(pump);
        return new HostGeneratorSession(root, pump,
                GeneratorRuntime.schedulerBacked(root, pump.token(), lowered), lowered);
    }

    static HostGeneratorSession fromTypecheckedProgram(
            OresScheduler root, HostGeneratorTurnPump contextPump,
            dev.oreslang.ast.Ast.Program program,
            String function, Map<String, ?> arguments) {
        var lowered = GeneratorRuntime.SourceLowering.compile(
                program, function, arguments);
        return new HostGeneratorSession(root, contextPump,
                GeneratorRuntime.schedulerBacked(root, contextPump.token(), lowered),
                lowered);
    }

    public OresFuture<GeneratorRuntime.Step<Object>> nextStep() {
        if (sealed.get()) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "generator session admission is sealed");
        }
        return pump.requestCertified(pump.token(), frame.nextTurn());
    }

    public void seal() {
        sealed.set(true);
        pump.seal();
    }

    public boolean isQuiescent() { return pump.isQuiescent(); }

    // Internal verification probes; not guest or public Polyglot capabilities.
    boolean awaitingSourceFuture() { return lowered.awaitingSourceFuture(); }
    int cleanupRuns() { return lowered.cleanupRuns(); }
    Object cleanupBinding(String name) { return lowered.cleanupBinding(name); }

    @Override
    public void close() {
        seal();
        pump.requireQuiescent();
    }
}
