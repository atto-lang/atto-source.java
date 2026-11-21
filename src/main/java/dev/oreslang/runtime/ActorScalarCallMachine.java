package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M3.3a: interpreter-free (per guest call) scalar activation dispatcher.
 *
 * <p>Only the compiler-approved pointer-free int/bool subset is admitted.
 * Every program counter, scalar local, and temporary operand of each live
 * activation resides in actor-owned, quota-charged {@link ActorFrameArena}
 * bytes. The Java deque stores only opaque frame capabilities/function IDs;
 * it never stores guest scalar locals, operands, or Java return addresses.
 * CALL does not recursively invoke Java. TAIL_CALL replaces one activation.
 *
 * <p>Not a general interpreter: object roots, closures, defer, exceptions,
 * async and suspension require separate typed lowering. The owning actor
 * carrier remains synchronous during this supported scalar call chain;
 * a bounded safepoint callback runs only every 128 dispatched instructions.
 */
public final class ActorScalarCallMachine {
    private static final int MAGIC = 0x41544f33; // ATO3
    private static final int HEADER = 16;
    private static final int SLOT = GuestScalarFrameCodec.SLOT_BYTES;
    private static final int CHECKPOINT_MASK = 127;

    public enum Op {
        CONST, CONST_BOOL, LOAD, STORE, ADD, SUB, MUL, LT, LE, EQ,
        JUMP, JUMP_IF_FALSE, CALL, TAIL_CALL, RET, POP, TRAP
    }

    public record Instruction(Op op, int argument, long value) {
        public Instruction {
            Objects.requireNonNull(op, "scalar instruction opcode");
        }
        public static Instruction of(Op op) { return new Instruction(op, 0, 0L); }
        public static Instruction at(Op op, int index) { return new Instruction(op, index, 0L); }
        public static Instruction constant(long value) { return new Instruction(Op.CONST, 0, value); }
    }

    /** Immutable, compiler-admitted function image, not a guest closure. */
    public record Function(String name, int parameters, int locals, int maxStack,
                           List<Instruction> code) {
        public Function {
            if (name == null || name.isBlank() || parameters < 0 || locals < parameters
                    || locals > 32 || maxStack < 1 || maxStack > 32) {
                throw new IllegalArgumentException("invalid scalar activation layout");
            }
            code = List.copyOf(code);
            if (code.isEmpty() || code.size() > 4096) {
                throw new IllegalArgumentException("invalid scalar instruction count");
            }
            int size = HEADER + SLOT * (locals + maxStack);
            if (size > ActorFrameArena.DEFAULT_MAX_BYTES) {
                throw new IllegalArgumentException("scalar frame exceeds actor byte budget");
            }
            for (Instruction step : code) {
                if (step.op() == Op.LOAD || step.op() == Op.STORE) {
                    if (step.argument() < 0 || step.argument() >= locals)
                        throw new IllegalArgumentException("scalar local index out of bounds");
                } else if (step.op() == Op.JUMP || step.op() == Op.JUMP_IF_FALSE) {
                    if (step.argument() < 0 || step.argument() >= code.size())
                        throw new IllegalArgumentException("scalar jump target out of bounds");
                } else if (step.op() == Op.CONST_BOOL && step.value() != 0 && step.value() != 1) {
                    throw new IllegalArgumentException("scalar Boolean must be canonical");
                }
            }
        }
        int frameBytes() { return HEADER + SLOT * (locals + maxStack); }
    }

    /** Indexed immutable call graph: direct calls only, no dynamic values. */
    public record Program(List<Function> functions) {
        public Program {
            functions = List.copyOf(functions);
            if (functions.isEmpty() || functions.size() > 256)
                throw new IllegalArgumentException("scalar program function count");
            for (Function function : functions) {
                for (Instruction instruction : function.code()) {
                    if ((instruction.op() == Op.CALL || instruction.op() == Op.TAIL_CALL)
                            && (instruction.argument() < 0
                                || instruction.argument() >= functions.size()))
                        throw new IllegalArgumentException("unresolved scalar call target");
                }
            }
        }
    }

    private record Activation(int functionId, ActorFrameArena.FrameRef ref) { }
    private record HeaderState(int pc, int sp) { }

    private ActorScalarCallMachine() { }

    /**
     * Runs only while the owning actor physical turn is admitted.
     * No execution of unsupported host callbacks or guest object values.
     *
     * @return the exact signed int/long scalar result
     */
    public static long execute(Program program, int entry, List<Long> arguments,
                               Runnable safepoint) {
        Objects.requireNonNull(program, "compiled scalar program");
        Objects.requireNonNull(arguments, "scalar function arguments");
        Objects.requireNonNull(safepoint, "actor scheduler safepoint");
        if (entry < 0 || entry >= program.functions().size())
            throw new IllegalArgumentException("scalar entry function");
        ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
        if (arena == null) throw new SecurityException(
                "scalar iterative dispatch requires an admitted actor turn");
        ExecutionContext execution = arena.newSynchronousExecution();
        ArrayDeque<Activation> activations = new ArrayDeque<>();
        long ticks = 0L;
        try {
            pushActivation(arena, execution, activations, program, entry, arguments);
            while (true) {
                if ((++ticks & CHECKPOINT_MASK) == 0L) safepoint.run();
                Activation active = activations.peekLast();
                if (active == null) throw new IllegalStateException("empty scalar call stack");
                Function fn = program.functions().get(active.functionId());
                HeaderState state = readHeader(arena, execution, active);
                if (state.sp() > fn.maxStack())
                    throw invalid(arena, "scalar operand stack pointer beyond admitted frame");
                if (state.pc() < 0 || state.pc() >= fn.code().size())
                    throw invalid(arena, "scalar PC outside compiled code");
                Instruction step = fn.code().get(state.pc());
                // Persist return-PC *before* CALL or TAIL_CALL replaces the
                // top activation; the current frame will be inaccessible
                // while a child activation owns the top of the actor stack.
                writeHeader(arena, execution, active, state.pc() + 1, state.sp());
                switch (step.op()) {
                    case CONST -> pushValue(arena, execution, active, fn, step.value());
                    case CONST_BOOL -> pushValue(arena, execution, active, fn,
                            step.value() != 0L);
                    case LOAD -> pushValue(arena, execution, active, fn,
                            readValue(arena, execution, active, step.argument()));
                    case STORE -> writeValue(arena, execution, active, step.argument(),
                            popValue(arena, execution, active, fn));
                    case POP -> popValue(arena, execution, active, fn);
                    case ADD, SUB, MUL, LT, LE, EQ -> {
                        Object right = popValue(arena, execution, active, fn);
                        Object left = popValue(arena, execution, active, fn);
                        Object result = switch (step.op()) {
                            case ADD -> asLong(left, arena) + asLong(right, arena);
                            case SUB -> asLong(left, arena) - asLong(right, arena);
                            case MUL -> asLong(left, arena) * asLong(right, arena);
                            case LT -> asLong(left, arena) < asLong(right, arena);
                            case LE -> asLong(left, arena) <= asLong(right, arena);
                            case EQ -> typedEqual(left, right, arena);
                            default -> throw new AssertionError();
                        };
                        pushValue(arena, execution, active, fn, result);
                    }
                    case JUMP -> {
                        HeaderState now = readHeader(arena, execution, active);
                        writeHeader(arena, execution, active, step.argument(), now.sp());
                    }
                    case JUMP_IF_FALSE -> {
                        Object predicate = popValue(arena, execution, active, fn);
                        if (!(predicate instanceof Boolean value))
                            throw invalid(arena, "scalar branch condition is not bool");
                        if (!value) {
                            HeaderState now = readHeader(arena, execution, active);
                            writeHeader(arena, execution, active, step.argument(), now.sp());
                        }
                    }
                    case CALL, TAIL_CALL -> {
                        Function callee = program.functions().get(step.argument());
                        ArrayList<Long> values = new ArrayList<>(callee.parameters());
                        for (int i = 0; i < callee.parameters(); i++)
                            values.add(0, asLong(popValue(arena, execution, active, fn), arena));
                        if (step.op() == Op.TAIL_CALL) {
                            arena.pop(execution, active.ref());
                            activations.removeLast();
                        }
                        pushActivation(arena, execution, activations,
                                program, step.argument(), values);
                    }
                    case TRAP -> throw invalid(arena, "scalar function fell through without return");
                    case RET -> {
                        Object result = popValue(arena, execution, active, fn);
                        if (!(result instanceof Long value))
                            throw invalid(arena, "scalar function must return int");
                        arena.pop(execution, active.ref());
                        activations.removeLast();
                        if (activations.isEmpty()) return value;
                        Activation parent = activations.peekLast();
                        pushValue(arena, execution, parent,
                                program.functions().get(parent.functionId()), value);
                    }
                }
            }
        } finally {
            // Cancellation, quota exhaustion, arithmetic failure, and
            // scheduler safepoint errors all reclaim precisely this logical
            // execution. The actor arena remains poisoned on hard limits.
            arena.cancel(execution);
            activations.clear();
        }
    }

    private static void pushActivation(
            ActorFrameArena arena, ExecutionContext execution,
            ArrayDeque<Activation> stack, Program program, int functionId,
            List<Long> values) {
        Function fn = program.functions().get(functionId);
        if (values.size() != fn.parameters())
            throw new IllegalArgumentException("scalar call arity mismatch");
        for (Long value : values) Objects.requireNonNull(value, "scalar argument");
        ActorFrameArena.FrameRef ref = arena.push(execution, fn.frameBytes());
        try {
            byte[] data = new byte[fn.frameBytes()];
            ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    .putInt(MAGIC).putInt(0).putInt(0).putInt(functionId);
            arena.write(execution, ref, 0, data);
            for (int i = 0; i < values.size(); i++)
                writeValue(arena, execution, new Activation(functionId, ref), i, values.get(i));
            stack.addLast(new Activation(functionId, ref));
        } catch (RuntimeException | Error failure) {
            arena.cancel(execution);
            throw failure;
        }
    }

    private static HeaderState readHeader(ActorFrameArena arena,
                                           ExecutionContext execution,
                                           Activation activation) {
        ByteBuffer bytes = ByteBuffer.wrap(
                arena.read(execution, activation.ref(), 0, HEADER))
                .order(ByteOrder.BIG_ENDIAN);
        int magic = bytes.getInt(), pc = bytes.getInt(), sp = bytes.getInt();
        int functionId = bytes.getInt();
        if (magic != MAGIC || pc < 0 || sp < 0
                || functionId != activation.functionId())
            throw invalid(arena, "corrupt scalar activation PC or callee identity");
        return new HeaderState(pc, sp);
    }

    private static void writeHeader(ActorFrameArena arena, ExecutionContext execution,
                                    Activation activation, int pc, int sp) {
        if (pc < 0 || sp < 0) throw invalid(arena, "negative activation cursor");
        byte[] bytes = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).putInt(pc).putInt(sp)
                .putInt(activation.functionId()).array();
        arena.write(execution, activation.ref(), 0, bytes);
    }

    private static void pushValue(ActorFrameArena arena, ExecutionContext execution,
                                  Activation frame, Function fn, Object value) {
        HeaderState state = readHeader(arena, execution, frame);
        if (state.sp() >= fn.maxStack())
            throw invalid(arena, "scalar operand stack exhausted");
        writeValue(arena, execution, frame, fn.locals() + state.sp(), value);
        writeHeader(arena, execution, frame, state.pc(), state.sp() + 1);
    }

    private static Object popValue(ActorFrameArena arena, ExecutionContext execution,
                                    Activation frame, Function fn) {
        HeaderState state = readHeader(arena, execution, frame);
        if (state.sp() == 0 || state.sp() > fn.maxStack())
            throw invalid(arena, "scalar operand stack underflow");
        int index = fn.locals() + state.sp() - 1;
        Object value = readValue(arena, execution, frame, index);
        arena.write(execution, frame.ref(), HEADER + index * SLOT, new byte[SLOT]);
        writeHeader(arena, execution, frame, state.pc(), state.sp() - 1);
        return value;
    }

    private static Object readValue(ActorFrameArena arena, ExecutionContext execution,
                                    Activation activation, int index) {
        byte[] bits = arena.read(execution, activation.ref(),
                HEADER + index * SLOT, SLOT);
        try {
            return GuestScalarFrameCodec.decode(bits).getFirst();
        } catch (RuntimeException malformed) {
            arena.poisonFromGuestGuard();
            throw malformed;
        }
    }

    private static void writeValue(ActorFrameArena arena, ExecutionContext execution,
                                   Activation activation, int index, Object value) {
        byte[] encoded = GuestScalarFrameCodec.encode(List.of(value));
        if (encoded == null || encoded.length != SLOT || !(value instanceof Long
                || value instanceof Boolean))
            throw invalid(arena, "unsupported scalar call value");
        arena.write(execution, activation.ref(), HEADER + index * SLOT, encoded);
    }

    private static long asLong(Object value, ActorFrameArena arena) {
        if (value instanceof Long number) return number;
        throw invalid(arena, "compiled scalar integer opcode encountered a non-int slot");
    }

    private static boolean typedEqual(Object left, Object right,
                                       ActorFrameArena arena) {
        if (left instanceof Long a && right instanceof Long b)
            return a.longValue() == b.longValue();
        if (left instanceof Boolean a && right instanceof Boolean b)
            return a.booleanValue() == b.booleanValue();
        // A mixed primitive-kind EQ cannot originate from the verified
        // source lowering. Treat this as a corrupted actor activation
        // rather than silently applying JVM object equality.
        throw invalid(arena, "compiled scalar equality has mismatched primitive types");
    }

    private static IllegalStateException invalid(ActorFrameArena arena, String message) {
        arena.poisonFromGuestGuard();
        return new IllegalStateException(message);
    }
}
