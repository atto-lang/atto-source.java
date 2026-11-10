package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.ScalarFrameStorage;
import dev.oreslang.runtime.ScalarFrameStorage.Scalar;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed scalar function compilation with explicit heap-owned call
 * frames. All reachable fnc bodies must be admitted before execution;
 * unsupported constructs use the old evaluator without partial effects.
 * The VM uses ONE Java dispatch loop for nested non-tail calls.
 */
final class ScalarFrameMachine {
    @FunctionalInterface interface Resolver {
        Ast.FunctionDecl resolve(String name, int arity);
    }
    @FunctionalInterface interface BinaryOp {
        Object apply(String operator, Object lhs, Object rhs);
    }
    private enum Op {
        CONST, LOAD, STORE, NEGATE, NOT, BIN, CALL, TAIL_CALL,
        JUMP_IF_FALSE, JUMP, RETURN
    }
    private record Instruction(Op op, Scalar value, int number,
                               String operator, Ast.FunctionDecl callee) {
        static Instruction simple(Op op) { return new Instruction(op, null, 0, null, null); }
        static Instruction indexed(Op op, int n) { return new Instruction(op, null, n, null, null); }
        static Instruction literal(Scalar v) { return new Instruction(Op.CONST, v, 0, null, null); }
        static Instruction binary(String operator) {
            return new Instruction(Op.BIN, null, 0, operator, null);
        }
        static Instruction call(Op op, Ast.FunctionDecl callee) {
            return new Instruction(op, null, 0, null, callee);
        }
    }
    private record Code(Ast.FunctionDecl fn, List<Instruction> ops,
                        int locals, int params, int maxOperands) { }
    static final class Plan {
        private final Ast.FunctionDecl entry;
        private final IdentityHashMap<Ast.FunctionDecl, Code> code;
        private Plan(Ast.FunctionDecl entry, IdentityHashMap<Ast.FunctionDecl, Code> code) {
            this.entry = entry;
            this.code = new IdentityHashMap<>(code);
        }
        int reachableFunctions() { return code.size(); }
    }
    private record Activation(Code code, ScalarFrameStorage.Frame frame) { }
    private static final class NotAdmitted extends RuntimeException {
        private NotAdmitted() { super(null, null, false, false); }
    }

    private final Resolver resolver;
    private final IdentityHashMap<Ast.FunctionDecl, Code> compiled = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.FunctionDecl, Boolean> visiting = new IdentityHashMap<>();
    private ScalarFrameMachine(Resolver resolver) { this.resolver = resolver; }

    static Plan compile(Ast.FunctionDecl fn, Resolver resolver) {
        ScalarFrameMachine compiler = new ScalarFrameMachine(Objects.requireNonNull(resolver));
        try {
            compiler.compileOne(Objects.requireNonNull(fn));
            return new Plan(fn, compiler.compiled);
        } catch (NotAdmitted unsupported) {
            return null;
        }
    }

    private static boolean intType(Ast.TypeRef type) {
        return type != null && type.name().equals("int")
                && type.arguments().isEmpty() && !type.inferArguments();
    }
    private static boolean definitelyReturns(List<Ast.Stmt> body) {
        return definitelyReturns(body, 0);
    }
    private static boolean definitelyReturns(List<Ast.Stmt> body, int depth) {
        if (depth > 64) return false;
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt ret) return ret.value() != null;
            if (stmt instanceof Ast.IfStmt branch && !branch.elseBody().isEmpty()) {
                boolean complete = definitelyReturns(branch.elseBody(), depth + 1);
                for (Ast.IfBranch arm : branch.branches()) {
                    complete &= definitelyReturns(arm.body(), depth + 1);
                }
                if (complete) return true;
            }
        }
        return false;
    }

    private void compileOne(Ast.FunctionDecl fn) {
        if (compiled.containsKey(fn) || visiting.containsKey(fn)) return;
        // A malicious or generated AST must never blow the JVM compiler
        // stack by expanding a huge cross-function graph before execution.
        if (compiled.size() + visiting.size() >= 32) throw new NotAdmitted();
        if (fn.kind() != Ast.CallableKind.FNC || fn.async() || fn.suspends()
                || fn.generator() || fn.trapped()
                || fn.actorKind() != Ast.ActorKind.NONE
                || !fn.genericParameters().isEmpty() || !fn.genericSpecs().isEmpty()
                || !fn.effects().isEmpty() || !fn.annotations().isEmpty()
                || !intType(fn.returnType()) || fn.name().equals("init")
                || fn.parameters().size() > 16 || fn.body().size() > 256
                || !definitelyReturns(fn.body())) throw new NotAdmitted();

        LinkedHashMap<String, Integer> slots = new LinkedHashMap<>();
        for (Ast.Param p : fn.parameters()) {
            if (!intType(p.type()) || p.mutable() || p.structural()
                    || slots.containsKey(p.name())) throw new NotAdmitted();
            slots.put(p.name(), slots.size());
        }
        for (Ast.Stmt stmt : fn.body()) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.kind() == Ast.BindingKind.LET
                        || binding.mutableReferent() || binding.inferredTypeName() != null
                        || (binding.declaredType() != null
                                && !intType(binding.declaredType()))
                        || slots.containsKey(binding.name())) throw new NotAdmitted();
                slots.put(binding.name(), slots.size());
            }
        }
        if (slots.size() > 32) throw new NotAdmitted();
        visiting.put(fn, true);
        try {
            Emitter emit = new Emitter(slots);
            for (Ast.Stmt stmt : fn.body()) emit.statement(stmt, false);
            if (emit.stackHeight != 0 || emit.peak > 64
                    || emit.instructions.size() > 4096) throw new NotAdmitted();
            compiled.put(fn, new Code(fn, List.copyOf(emit.instructions),
                    slots.size(), fn.parameters().size(), Math.max(1, emit.peak)));
        } finally {
            visiting.remove(fn);
        }
    }

    private final class Emitter {
        final LinkedHashMap<String, Integer> slots;
        final ArrayList<Instruction> instructions = new ArrayList<>();
        int stackHeight, peak, expressionDepth, statementDepth;
        Emitter(LinkedHashMap<String, Integer> slots) { this.slots = slots; }
        void height(int delta) {
            stackHeight += delta;
            if (stackHeight < 0) throw new NotAdmitted();
            peak = Math.max(peak, stackHeight);
        }
        int emit(Instruction i) { instructions.add(i); return instructions.size() - 1; }
        void patch(int index, int destination) {
            Instruction old = instructions.get(index);
            instructions.set(index, Instruction.indexed(old.op(), destination));
        }
        void statement(Ast.Stmt stmt, boolean nested) {
            if (++statementDepth > 64) {
                statementDepth--;
                throw new NotAdmitted();
            }
            try {
            if (stmt instanceof Ast.BindingStmt binding && !nested) {
                expr(binding.initializer(), false);
                emit(Instruction.indexed(Op.STORE, slots.get(binding.name())));
                height(-1);
            } else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) {
                expr(ret.value(), true);
                if (!(ret.value() instanceof Ast.CallExpr)) {
                    emit(Instruction.simple(Op.RETURN));
                    height(-1);
                }
            } else if (stmt instanceof Ast.IfStmt branch
                    && !branch.branches().isEmpty()) {
                ArrayList<Integer> ends = new ArrayList<>();
                for (Ast.IfBranch arm : branch.branches()) {
                    expr(arm.condition(), false);
                    int jf = emit(Instruction.indexed(Op.JUMP_IF_FALSE, -1));
                    height(-1);
                    for (Ast.Stmt member : arm.body()) statement(member, true);
                    ends.add(emit(Instruction.indexed(Op.JUMP, -1)));
                    patch(jf, instructions.size());
                }
                for (Ast.Stmt member : branch.elseBody()) statement(member, true);
                int end = instructions.size();
                for (int index : ends) patch(index, end);
            } else throw new NotAdmitted();
            } finally {
                statementDepth--;
            }
        }

        void expr(Ast.Expr node, boolean tailPosition) {
            if (++expressionDepth > 64) {
                expressionDepth--;
                throw new NotAdmitted();
            }
            try {
            if (node instanceof Ast.LiteralExpr literal) {
                Object v = literal.value();
                if (!(v instanceof Boolean || v instanceof Integer || v instanceof Long
                        || v instanceof Byte || v instanceof Short)) throw new NotAdmitted();
                emit(Instruction.literal(Scalar.from(v)));
                height(1);
            } else if (node instanceof Ast.NameExpr name) {
                Integer slot = slots.get(name.name());
                if (slot == null) throw new NotAdmitted();
                emit(Instruction.indexed(Op.LOAD, slot));
                height(1);
            } else if (node instanceof Ast.UnaryExpr unary) {
                if (!List.of("+", "-", "!").contains(unary.operator()))
                    throw new NotAdmitted();
                expr(unary.operand(), false);
                if (unary.operator().equals("-")) emit(Instruction.simple(Op.NEGATE));
                if (unary.operator().equals("!")) emit(Instruction.simple(Op.NOT));
            } else if (node instanceof Ast.BinaryExpr binary) {
                if (!List.of("+", "-", "*", "<", "<=", ">", ">=",
                        "==", "!=", "eq", "neq").contains(binary.operator())) throw new NotAdmitted();
                expr(binary.left(), false);
                expr(binary.right(), false);
                emit(Instruction.binary(binary.operator()));
                height(-1);
            } else if (node instanceof Ast.CallExpr call
                    && call.origin() == Ast.CallOrigin.DIRECT
                    && !call.typeArgumentsPresent() && call.typeArguments().isEmpty()
                    && call.callee() instanceof Ast.NameExpr name
                    && !slots.containsKey(name.name())) {
                Ast.FunctionDecl callee;
                try { callee = resolver.resolve(name.name(), call.arguments().size()); }
                catch (IllegalArgumentException error) { throw new NotAdmitted(); }
                if (callee == null) throw new NotAdmitted();
                compileOne(callee);
                for (Ast.Expr arg : call.arguments()) expr(arg, false);
                emit(Instruction.call(tailPosition ? Op.TAIL_CALL : Op.CALL, callee));
                height(tailPosition ? -call.arguments().size() : 1 - call.arguments().size());
            } else throw new NotAdmitted();
            } finally {
                expressionDepth--;
            }
        }
    }

    /**
     * The runtime never interprets unsupported AST nodes. All PCs, operand
     * slots, and locals are stored in typed actor-owned frames when an actor
     * is active. Host activation records carry only immutable Code refs.
     */
    static Object execute(Plan plan, List<Object> inputs,
                          BinaryOp binary, Runnable safepoint) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(binary);
        Objects.requireNonNull(safepoint);
        Code entry = plan.code.get(plan.entry);
        if (inputs.size() != entry.params()) {
            throw new IllegalArgumentException("scalar fnc argument count mismatch");
        }
        try (ScalarFrameStorage memory = new ScalarFrameStorage()) {
            ArrayDeque<Activation> callStack = new ArrayDeque<>();
            callStack.addLast(new Activation(entry, push(memory, entry, inputs)));
            long reductions = 0;
            while (true) {
                if ((++reductions & 63L) == 0L) safepoint.run();
                Activation active = callStack.peekLast();
                ScalarFrameStorage.Frame frame = active.frame();
                int pc = frame.pc();
                Code code = active.code();
                if (pc < 0 || pc >= code.ops().size()) {
                    throw new IllegalStateException("invalid scalar frame instruction pointer");
                }
                Instruction ins = code.ops().get(pc);
                frame.setPc(pc + 1);
                switch (ins.op()) {
                    case CONST -> frame.push(ins.value());
                    case LOAD -> frame.push(frame.local(ins.number()));
                    case STORE -> frame.putLocal(ins.number(), frame.pop());
                    case NEGATE -> frame.push(Scalar.ofLong(-frame.pop().asLong()));
                    case NOT -> frame.push(Scalar.ofBoolean(!frame.pop().asBoolean()));
                    case BIN -> {
                        Scalar right = frame.pop(), left = frame.pop();
                        frame.push(Scalar.from(binary.apply(ins.operator(),
                                left.boxed(), right.boxed())));
                    }
                    case JUMP_IF_FALSE -> {
                        if (!frame.pop().asBoolean()) frame.setPc(ins.number());
                    }
                    case JUMP -> frame.setPc(ins.number());
                    case CALL, TAIL_CALL -> {
                        Code callee = plan.code.get(ins.callee());
                        if (callee == null) throw new IllegalStateException("missing typed callee");
                        ArrayList<Object> args = new ArrayList<>(callee.params());
                        for (int i = 0; i < callee.params(); ++i) args.add(null);
                        for (int i = callee.params() - 1; i >= 0; --i) {
                            args.set(i, frame.pop().boxed());
                        }
                        if (ins.op() == Op.TAIL_CALL) {
                            callStack.removeLast();
                            memory.leave(frame);
                        }
                        callStack.addLast(new Activation(callee, push(memory, callee, args)));
                    }
                    case RETURN -> {
                        Scalar value = frame.pop();
                        if (value.tag() != Scalar.INT) {
                            throw new IllegalStateException("int function returned a non-int scalar");
                        }
                        callStack.removeLast();
                        memory.leave(frame);
                        if (callStack.isEmpty()) return value.boxed();
                        callStack.peekLast().frame().push(value);
                    }
                }
            }
        }
    }

    private static ScalarFrameStorage.Frame push(
            ScalarFrameStorage memory, Code code, List<?> values) {
        ScalarFrameStorage.Frame frame = memory.enter(code.locals(), code.maxOperands());
        for (int i = 0; i < values.size(); ++i) {
            frame.putLocal(i, Scalar.from(values.get(i)));
        }
        return frame;
    }
}
