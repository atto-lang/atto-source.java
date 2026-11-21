package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.GuestInvocationFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed, stackless interpreter lowering for a closed subset of AttoLang
 * pure scalar fnc declarations. Never guesses how unsupported syntax behaves.
 *
 * <p>Entry points and the entire reachable direct-call graph are checked
 * before any execution or frame admission. The iterative execution loop
 * carries return PCs and bounded primitive operand values in host-owned
 * activation records; one GuestInvocationFrame lease per non-tail call
 * charges the actor's guarded frame arena. Proper tail calls replace the
 * current activation and rewrite that same lease, rather than recursively
 * calling Evaluator.invoke or growing the carrier's native stack.
 *
 * <p>M3-early: the evaluator still has recursive paths for non-eligible
 * programs. Operands and return PCs are Java-managed and not yet persisted
 * across SourceTask suspension. This is not full physical stack isolation.
 */
final class ScalarFrameMachine {
    private static final int MAX_FUNCTIONS = 64;
    private static final int MAX_OPS_PER_FUNCTION = 1024;
    private static final int MAX_AST_DEPTH = 64;
    private static final int MAX_OPERAND_VALUES = 64;
    private static final int SAFEPOINT_INTERVAL = 64;

    interface Host {
        Ast.FunctionDecl resolve(String name, int arity);
        Object binary(String operator, Object left, Object right);
        Object shapeReturn(Ast.FunctionDecl fn, Object value);
        void safepoint();
    }

    private enum Kind { LITERAL, PARAM, BINARY, CALL, TAIL_CALL, IF_FALSE, JUMP, RETURN }

    private static final class Op {
        final Kind kind;
        final Object data;
        int position;
        Op(Kind kind, Object data, int position) {
            this.kind = kind;
            this.data = data;
            this.position = position;
        }
    }

    private record Code(Ast.FunctionDecl fn, List<Op> ops) { }

    static final class Program {
        private final Ast.FunctionDecl entry;
        private final Map<Ast.FunctionDecl, Code> code;

        private Program(Ast.FunctionDecl entry, Map<Ast.FunctionDecl, Code> code) {
            this.entry = entry;
            this.code = code;
        }
    }

    private static final class Unsupported extends RuntimeException {
        private Unsupported() { super(null, null, false, false); }
    }
    private static final Unsupported UNSUPPORTED = new Unsupported();

    /**
     * Compile only a closed graph. Unsupported constructs select the ordinary
     * guarded evaluator, BEFORE any machine execution; there is no partial
     * execution followed by legacy fallback and no speculative side effect.
     */
    static Program prepare(Ast.FunctionDecl entry, Host host) {
        if (!eligible(entry)) return null;
        try {
            Map<Ast.FunctionDecl, Code> code = new IdentityHashMap<>();
            ArrayDeque<Ast.FunctionDecl> pending = new ArrayDeque<>();
            pending.add(entry);
            while (!pending.isEmpty()) {
                Ast.FunctionDecl fn = pending.removeFirst();
                if (code.containsKey(fn)) continue;
                if (code.size() >= MAX_FUNCTIONS || !eligible(fn)) throw UNSUPPORTED;
                Compiler compiler = new Compiler(fn, host, pending);
                Code result = compiler.build();
                code.put(fn, result);
            }
            return new Program(entry, code);
        } catch (Unsupported ignored) {
            return null;
        }
    }

    private static boolean eligible(Ast.FunctionDecl fn) {
        if (fn == null || fn.kind() != Ast.CallableKind.FNC
                || fn.name().equals("init")
                || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator()
                || fn.structural() || fn.nonLexical() || fn.trapped()
                || !fn.genericParameters().isEmpty() || !fn.genericSpecs().isEmpty()
                || !fn.annotations().isEmpty() || !fn.effects().isEmpty()
                || fn.parameters().size() > 2 || fn.body().isEmpty()
                || fn.returnType() == null || !plainInt(fn.returnType())) {
            return false;
        }
        java.util.HashSet<String> parameterNames = new java.util.HashSet<>();
        for (Ast.Param param : fn.parameters()) {
            if (!plainInt(param.type()) || param.structural() || param.mutable()
                    || param.name() == null || !parameterNames.add(param.name())) {
                return false;
            }
        }
        return true;
    }

    private static boolean plainInt(Ast.TypeRef type) {
        return type != null && type.name().equals("int")
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static final class Compiler {
        private final Ast.FunctionDecl fn;
        private final Host host;
        private final ArrayDeque<Ast.FunctionDecl> pending;
        private final ArrayList<Op> ops = new ArrayList<>();

        private Compiler(Ast.FunctionDecl fn, Host host,
                         ArrayDeque<Ast.FunctionDecl> pending) {
            this.fn = fn;
            this.host = host;
            this.pending = pending;
        }

        private Code build() {
            if (!statements(fn.body(), 0)) throw UNSUPPORTED;
            return new Code(fn, List.copyOf(ops));
        }

        private int emit(Kind kind, Object data, int position) {
            if (ops.size() >= MAX_OPS_PER_FUNCTION) throw UNSUPPORTED;
            ops.add(new Op(kind, data, position));
            return ops.size() - 1;
        }

        private boolean statements(List<Ast.Stmt> statements, int depth) {
            if (depth > MAX_AST_DEPTH || statements.isEmpty()) throw UNSUPPORTED;
            boolean returned = false;
            for (Ast.Stmt stmt : statements) {
                if (returned) throw UNSUPPORTED; // no silently discarded statements
                if (stmt instanceof Ast.ReturnStmt ret) {
                    if (ret.value() == null) throw UNSUPPORTED;
                    if (ret.value() instanceof Ast.CallExpr call) {
                        call(call, true, depth + 1);
                    } else {
                        expression(ret.value(), depth + 1);
                        emit(Kind.RETURN, null, 0);
                    }
                    returned = true;
                } else if (stmt instanceof Ast.IfStmt conditional) {
                    if (conditional.branches().isEmpty()
                            || conditional.elseBody().isEmpty()) throw UNSUPPORTED;
                    List<Integer> exits = new ArrayList<>();
                    boolean allReturned = true;
                    for (Ast.IfBranch branch : conditional.branches()) {
                        expression(branch.condition(), depth + 1);
                        int next = emit(Kind.IF_FALSE, null, -1);
                        allReturned &= statements(branch.body(), depth + 1);
                        exits.add(emit(Kind.JUMP, null, -1));
                        ops.get(next).position = ops.size();
                    }
                    allReturned &= statements(conditional.elseBody(), depth + 1);
                    for (int exit : exits) ops.get(exit).position = ops.size();
                    returned = allReturned;
                } else {
                    throw UNSUPPORTED;
                }
            }
            return returned;
        }

        private void expression(Ast.Expr expr, int depth) {
            if (depth > MAX_AST_DEPTH) throw UNSUPPORTED;
            if (expr instanceof Ast.LiteralExpr literal) {
                Object value = literal.value();
                if (!(value instanceof Byte || value instanceof Short
                        || value instanceof Integer || value instanceof Long)) {
                    throw UNSUPPORTED;
                }
                emit(Kind.LITERAL, value, 0);
            } else if (expr instanceof Ast.NameExpr name) {
                int slot = -1;
                for (int i = 0; i < fn.parameters().size(); i++) {
                    if (fn.parameters().get(i).name().equals(name.name())) slot = i;
                }
                if (slot < 0) throw UNSUPPORTED;
                emit(Kind.PARAM, null, slot);
            } else if (expr instanceof Ast.BinaryExpr binary) {
                if (!List.of("+", "-", "*", "eq", "==", "neq", "!=", "<",
                        "<=", ">", ">=").contains(binary.operator())) throw UNSUPPORTED;
                expression(binary.left(), depth + 1);
                expression(binary.right(), depth + 1);
                emit(Kind.BINARY, binary.operator(), 0);
            } else if (expr instanceof Ast.CallExpr call) {
                call(call, false, depth + 1);
            } else {
                throw UNSUPPORTED;
            }
        }

        private void call(Ast.CallExpr call, boolean tail, int depth) {
            if (depth > MAX_AST_DEPTH || call.origin() != Ast.CallOrigin.DIRECT
                    || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                    || !(call.callee() instanceof Ast.NameExpr name)) throw UNSUPPORTED;
            Ast.FunctionDecl target;
            try {
                target = host.resolve(name.name(), call.arguments().size());
            } catch (IllegalArgumentException ambiguous) {
                throw UNSUPPORTED; // legacy evaluator reports the real error
            }
            if (!eligible(target)) throw UNSUPPORTED;
            for (Ast.Expr argument : call.arguments()) expression(argument, depth + 1);
            pending.addLast(target);
            emit(tail ? Kind.TAIL_CALL : Kind.CALL, target, call.arguments().size());
        }
    }

    private static final class Activation {
        Code code;
        long[] params;
        final ArrayDeque<Object> operands = new ArrayDeque<>();
        int pc;
        final GuestInvocationFrame lease;
        long tailHops;

        Activation(Code code, long[] params, GuestInvocationFrame lease) {
            this.code = code;
            this.lease = lease;
            lease.writeScalarArguments(params);
            // For actors, read actual scalar parameters from owned frame
            // bytes. The Java array is kept only for non-actor host calls.
            this.params = lease.hasActorOwnedScalarSlots() ? null : params.clone();
        }

        long parameter(int index) {
            return params == null
                    ? lease.readScalarArgument(index)
                    : params[index];
        }

        void transfer(Code next, long[] arguments) {
            lease.tailTransfer(0, arguments.length, ++tailHops);
            lease.writeScalarArguments(arguments);
            code = next;
            params = lease.hasActorOwnedScalarSlots() ? null : arguments.clone();
            pc = 0;
            operands.clear();
        }
    }

    private static Activation activate(Code code, long[] args) {
        GuestInvocationFrame lease = GuestInvocationFrame.enter(0, args.length);
        try {
            return new Activation(code, args, lease);
        } catch (RuntimeException | Error failure) {
            try {
                lease.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void push(Activation activation, Object value) {
        if (activation.operands.size() >= MAX_OPERAND_VALUES) {
            throw new IllegalStateException("scalar activation operand budget exceeded");
        }
        activation.operands.addLast(value);
    }

    private static long[] takeArguments(Activation caller, int arity) {
        if (arity < 0 || arity > 2 || caller.operands.size() < arity) {
            throw new IllegalStateException("invalid scalar call operand state");
        }
        long[] args = new long[arity];
        for (int i = arity - 1; i >= 0; i--) {
            Object value = caller.operands.removeLast();
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("scalar argument must be an integer");
            }
            args[i] = number.longValue();
        }
        return args;
    }

    static Object run(Program program, List<Object> initialArgs, Host host) {
        if (initialArgs.size() != program.entry.parameters().size()) {
            throw new IllegalArgumentException("scalar function arity mismatch");
        }
        long[] args = new long[initialArgs.size()];
        for (int i = 0; i < args.length; i++) {
            if (!(initialArgs.get(i) instanceof Number number)
                    || !(number instanceof Byte || number instanceof Short
                         || number instanceof Integer || number instanceof Long)) {
                throw new IllegalArgumentException("scalar function requires integer arguments");
            }
            args[i] = number.longValue();
        }

        ArrayDeque<Activation> frames = new ArrayDeque<>();
        long instructions = 0;
        try {
            Code first = program.code.get(program.entry);
            frames.addLast(activate(first, args));
            while (!frames.isEmpty()) {
                Activation frame = frames.peekLast();
                if (frame.pc < 0 || frame.pc >= frame.code.ops().size()) {
                    throw new IllegalStateException("scalar frame program counter outside function");
                }
                if ((++instructions % SAFEPOINT_INTERVAL) == 0) host.safepoint();
                Op op = frame.code.ops().get(frame.pc++);
                switch (op.kind) {
                    case LITERAL -> push(frame, op.data);
                    case PARAM -> push(frame, frame.parameter(op.position));
                    case BINARY -> {
                        Object right = frame.operands.removeLast();
                        Object left = frame.operands.removeLast();
                        push(frame, host.binary((String) op.data, left, right));
                    }
                    case IF_FALSE -> {
                        Object value = frame.operands.removeLast();
                        if (!(value instanceof Boolean condition)) {
                            throw new IllegalArgumentException("condition must be bool");
                        }
                        if (!condition) frame.pc = op.position;
                    }
                    case JUMP -> frame.pc = op.position;
                    case CALL, TAIL_CALL -> {
                        Ast.FunctionDecl target = (Ast.FunctionDecl) op.data;
                        Code next = program.code.get(target);
                        if (next == null) throw new IllegalStateException("uncompiled scalar callee");
                        long[] callArgs = takeArguments(frame, op.position);
                        if (op.kind == Kind.TAIL_CALL) {
                            frame.transfer(next, callArgs);
                        } else {
                            frames.addLast(activate(next, callArgs));
                        }
                    }
                    case RETURN -> {
                        if (frame.operands.size() != 1) {
                            throw new IllegalStateException("unbalanced scalar return operands");
                        }
                        Object result = host.shapeReturn(frame.code.fn(), frame.operands.removeLast());
                        frame.lease.close();
                        frames.removeLast();
                        if (frames.isEmpty()) return result;
                        push(frames.peekLast(), result);
                    }
                }
            }
            throw new IllegalStateException("scalar machine ended without return");
        } finally {
            // A failed deeper frame must never prevent the remaining leases
            // from unwinding on this same carrier. Preserve the original
            // exception while trying every LIFO release.
            while (!frames.isEmpty()) {
                Activation leftover = frames.removeLast();
                try {
                    leftover.lease.close();
                } catch (RuntimeException | Error ignored) {
                    // Original failure remains primary; scheduler/actor
                    // poisoning in the guard prevents stale guest admission.
                }
            }
        }
    }
}
