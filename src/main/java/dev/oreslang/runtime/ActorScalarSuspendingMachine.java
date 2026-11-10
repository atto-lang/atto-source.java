package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Restricted, compiler-fed suspending scalar CALL machine.
 *
 * <p>Every program counter, scalar local, return location, and operand is
 * persisted in the SourceTask's actor-owned ActorCallContinuation frames.
 * Awaited Futures are trusted scheduler-side ports; no Java object pointer,
 * Future, closure, or native return address is stored in guest frame bytes.
 * Physical carriers are released at AWAIT_PORT or cooperative checkpoints.
 *
 * <p>This is an opt-in typed scalar subset, not the general AttoLang guest
 * compiler and not a native stack. A receive that invokes this Task must use
 * suspendCurrentUserMailboxUntil on the returned Future for serial admission.
 */
public final class ActorScalarSuspendingMachine {
    public enum Op { CONST, LOAD, STORE, ADD, CALL, AWAIT_PORT, RET }

    public record Instruction(Op op, int argument, long literal) {
        public Instruction { Objects.requireNonNull(op); }
        public static Instruction at(Op op, int argument) {
            return new Instruction(op, argument, 0L);
        }
        public static Instruction of(Op op) { return at(op, 0); }
        public static Instruction constant(long value) {
            return new Instruction(Op.CONST, 0, value);
        }
    }

    public record Function(String name, int parameters, int locals,
                           int maxStack, List<Instruction> code) {
        public Function {
            if (name == null || name.isBlank() || parameters < 0
                    || locals < parameters || locals > 32
                    || maxStack < 1 || maxStack > 32) {
                throw new IllegalArgumentException("invalid suspendable scalar function layout");
            }
            code = List.copyOf(code);
            if (code.isEmpty() || code.size() > 4096) {
                throw new IllegalArgumentException("invalid suspendable scalar code length");
            }
            for (Instruction insn : code) {
                if ((insn.op() == Op.LOAD || insn.op() == Op.STORE)
                        && (insn.argument() < 0 || insn.argument() >= locals)) {
                    throw new IllegalArgumentException("scalar local index outside frame");
                }
            }
            long bytes = 28L + 9L * (locals + maxStack);
            if (bytes > ActorFrameArena.DEFAULT_MAX_BYTES) {
                throw new IllegalArgumentException("suspendable scalar frame exceeds arena budget");
            }
        }
    }

    public record Program(List<Function> functions, List<String> portNames) {
        public Program {
            functions = List.copyOf(functions);
            portNames = List.copyOf(portNames);
            if (functions.isEmpty() || functions.size() > 256
                    || portNames.size() > 32
                    || portNames.stream().distinct().count() != portNames.size()) {
                throw new IllegalArgumentException("invalid suspendable scalar program");
            }
            for (Function fn : functions) {
                for (Instruction step : fn.code()) {
                    if (step.op() == Op.CALL
                            && (step.argument() < 0 || step.argument() >= functions.size())) {
                        throw new IllegalArgumentException("unresolved scalar callee");
                    }
                    if (step.op() == Op.AWAIT_PORT
                            && (step.argument() < 0 || step.argument() >= portNames.size())) {
                        throw new IllegalArgumentException("unresolved async port");
                    }
                }
            }
        }
    }

    private ActorScalarSuspendingMachine() { }

    /**
     * Construct a one-shot scheduler state machine from a verified source plan.
     * One Task instance may be started only once. Port Futures are immutable
     * scheduler capabilities, never guest frame contents.
     */
    public static OresScheduler.Task<Long> task(
            Program program, int entry, List<Long> arguments,
            Map<String, OresFuture<Long>> ports) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(arguments);
        Objects.requireNonNull(ports);
        if (entry < 0 || entry >= program.functions().size()) {
            throw new IllegalArgumentException("invalid suspendable entry");
        }
        Function root = program.functions().get(entry);
        if (arguments.size() != root.parameters()) {
            throw new IllegalArgumentException("invalid suspendable entry arity");
        }
        List<Long> parameters = List.copyOf(arguments);
        List<OresFuture<Long>> awaiting = new ArrayList<>(program.portNames().size());
        if (ports.size() != program.portNames().size()) {
            throw new IllegalArgumentException("unexpected async port authorities");
        }
        for (String name : program.portNames()) {
            OresFuture<Long> future = Objects.requireNonNull(
                    ports.get(name), "missing authorized future port " + name);
            awaiting.add(future);
        }
        return new Invocation(program, entry, parameters, List.copyOf(awaiting));
    }

    private static final class Invocation implements OresScheduler.Task<Long> {
        private static final int INSTRUCTIONS_PER_TURN = 2048;
        private final Program program;
        private final int entry;
        private final List<Long> parameters;
        private final List<OresFuture<Long>> ports;
        private boolean started;
        private boolean pendingAwait;
        private boolean completed;

        Invocation(Program program, int entry, List<Long> parameters,
                   List<OresFuture<Long>> ports) {
            this.program = program;
            this.entry = entry;
            this.parameters = parameters;
            this.ports = ports;
        }

        @Override
        public OresScheduler.Step<Long> resume(OresScheduler.Resume resumed) {
            Objects.requireNonNull(resumed, "scheduler resume witness");
            ActorSourceTaskFrame source = OresScheduler.currentActorSourceTaskFrame();
            if (source == null) {
                throw new SecurityException("scalar suspension needs an admitted actor SourceTask");
            }
            ActorCallContinuation chain = source.callContinuation();
            if (completed) throw new IllegalStateException("scalar invocation already completed");
            if (started == resumed.initial()) {
                throw new SecurityException("invalid initial or duplicate scalar resume");
            }
            if (!started) {
                started = true;
                Function fn = program.functions().get(entry);
                chain.pushFrame(entry, 0, fn.locals(), fn.maxStack());
                for (int i = 0; i < parameters.size(); i++) {
                    chain.writeLocal(i, parameters.get(i));
                }
            } else if (pendingAwait) {
                pendingAwait = false;
                if (resumed.failure() != null) {
                    Throwable failure = resumed.failure();
                    if (failure instanceof RuntimeException error) throw error;
                    if (failure instanceof Error fatal) throw fatal;
                    throw new IllegalStateException("scalar async port failed", failure);
                }
                if (!(resumed.value() instanceof Long value)) {
                    throw new IllegalStateException("scalar async port must return a long");
                }
                chain.pushOperand(value);
            } else if (resumed.failure() != null) {
                throw new IllegalStateException("cooperative scalar task failed", resumed.failure());
            }

            for (int steps = 0; steps < INSTRUCTIONS_PER_TURN; steps++) {
                int id = chain.functionId();
                Function fn = program.functions().get(id);
                int pc = chain.programCounter();
                if (pc < 0 || pc >= fn.code().size()) {
                    throw new IllegalStateException("scalar suspension PC outside compiled image");
                }
                Instruction step = fn.code().get(pc);
                chain.setProgramCounter(pc + 1);
                switch (step.op()) {
                    case CONST -> chain.pushOperand(step.literal());
                    case LOAD -> chain.pushOperand(chain.readLocal(step.argument()));
                    case STORE -> chain.writeLocal(step.argument(), chain.popOperand());
                    case ADD -> {
                        long right = scalarLong(chain.popOperand());
                        long left = scalarLong(chain.popOperand());
                        chain.pushOperand(Math.addExact(left, right));
                    }
                    case CALL -> {
                        Function callee = program.functions().get(step.argument());
                        var values = new ArrayList<Long>(callee.parameters());
                        for (int i = 0; i < callee.parameters(); i++) {
                            values.add(0, scalarLong(chain.popOperand()));
                        }
                        chain.pushFrame(step.argument(), pc + 1,
                                callee.locals(), callee.maxStack());
                        for (int i = 0; i < values.size(); i++) {
                            chain.writeLocal(i, values.get(i));
                        }
                    }
                    case AWAIT_PORT -> {
                        pendingAwait = true;
                        return OresScheduler.await(ports.get(step.argument()));
                    }
                    case RET -> {
                        long result = scalarLong(chain.popOperand());
                        int returnPc = chain.returnProgramCounter();
                        chain.popFrame();
                        if (chain.depth() == 0) {
                            completed = true;
                            return OresScheduler.done(result);
                        }
                        chain.setProgramCounter(returnPc);
                        chain.pushOperand(result);
                    }
                }
            }
            // The same call chain survives a bounded voluntary preemption.
            return OresScheduler.cooperate();
        }

        private static long scalarLong(Object value) {
            if (value instanceof Long scalar) return scalar;
            throw new IllegalStateException("uninitialized or non-long scalar activation");
        }
    }
}
