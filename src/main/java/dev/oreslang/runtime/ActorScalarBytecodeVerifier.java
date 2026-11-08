package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * M3.3c independent, control-flow-aware validation of the immutable scalar
 * bytecode image. Runs BEFORE actor frame admission, guest execution or any
 * actor-generation poisoning. Unlike the source lowerer's linear estimate,
 * this proves operand types and definite local initialization at each
 * reachable PC, including loops and joins.
 *
 * No guest object, closure, pointer, capability or host callback is stored
 * in a verified operand. Every normal exit returns exactly INT (or traps);
 * nonterminating loops are allowed and remain subject to runtime fuel and
 * scheduler checkpoints. A bounded worklist reaches a monotone fixed point
 * for definite assignment.
 */
final class ActorScalarBytecodeVerifier {
    private ActorScalarBytecodeVerifier() { }

    enum Kind { INT, BOOL }

    static final class Rejected extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Rejected(String message) { super(message); }
    }

    private record State(List<Kind> stack, BitSet initialized) {
        State {
            stack = List.copyOf(stack);
            initialized = (BitSet) initialized.clone();
        }
    }

    static void verify(List<ActorScalarCallMachine.Function> functions) {
        Objects.requireNonNull(functions, "scalar functions");
        for (int functionId = 0; functionId < functions.size(); functionId++) {
            verifyFunction(functions, functionId);
        }
    }

    private static void verifyFunction(
            List<ActorScalarCallMachine.Function> functions, int functionId) {
        var function = functions.get(functionId);
        // Validate the *entire* immutable image, including unreachable
        // instructions, so unused operand/target fields never become
        // alternate encodings or executable after a future optimizer pass.
        for (int pc = 0; pc < function.code().size(); pc++) {
            var step = function.code().get(pc);
            boolean argumentUsed = switch (step.op()) {
                case LOAD, STORE, JUMP, JUMP_IF_FALSE, CALL, TAIL_CALL -> true;
                default -> false;
            };
            boolean valueUsed = switch (step.op()) {
                case CONST, CONST_BOOL -> true;
                default -> false;
            };
            if ((!argumentUsed && step.argument() != 0)
                    || (!valueUsed && step.value() != 0L)) {
                throw reject(function, pc, "noncanonical scalar instruction encoding");
            }
        }
        int n = function.code().size();
        State[] states = new State[n];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        BitSet initialized = new BitSet(function.locals());
        initialized.set(0, function.parameters());
        states[0] = new State(List.of(), initialized);
        pending.add(0);
        int visits = 0;
        // State is finite: one exact operand-type vector per PC, and
        // a monotonically decreasing initialized-local set at joins.
        int limit = Math.max(4096, n * (function.locals() + 2) * 4);

        while (!pending.isEmpty()) {
            if (++visits > limit)
                throw reject(function, -1, "control-flow analysis exceeded finite budget");
            int pc = pending.removeFirst();
            State current = states[pc];
            ArrayList<Kind> stack = new ArrayList<>(current.stack());
            BitSet init = (BitSet) current.initialized().clone();
            var instruction = function.code().get(pc);
            int arg = instruction.argument();

            switch (instruction.op()) {
                case CONST -> {
                    stack.add(Kind.INT);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case CONST_BOOL -> {
                    stack.add(Kind.BOOL);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case LOAD -> {
                    if (!init.get(arg))
                        throw reject(function, pc, "uninitialized int local " + arg);
                    stack.add(Kind.INT);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case STORE -> {
                    pop(function, pc, stack, Kind.INT);
                    init.set(arg);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case POP -> {
                    popAny(function, pc, stack);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case ADD, SUB, MUL -> {
                    pop(function, pc, stack, Kind.INT);
                    pop(function, pc, stack, Kind.INT);
                    stack.add(Kind.INT);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case LT, LE -> {
                    pop(function, pc, stack, Kind.INT);
                    pop(function, pc, stack, Kind.INT);
                    stack.add(Kind.BOOL);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case EQ -> {
                    Kind right = popAny(function, pc, stack);
                    Kind left = popAny(function, pc, stack);
                    if (left != right)
                        throw reject(function, pc, "mixed primitive types in equality");
                    stack.add(Kind.BOOL);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case JUMP -> successor(
                        function, states, pending, pc, arg, stack, init);
                case JUMP_IF_FALSE -> {
                    pop(function, pc, stack, Kind.BOOL);
                    successor(function, states, pending, pc, arg, stack, init);
                    successor(function, states, pending, pc, pc + 1, stack, init);
                }
                case CALL, TAIL_CALL -> {
                    var callee = functions.get(arg);
                    for (int i = 0; i < callee.parameters(); i++)
                        pop(function, pc, stack, Kind.INT);
                    if (instruction.op() == ActorScalarCallMachine.Op.TAIL_CALL) {
                        if (!stack.isEmpty())
                            throw reject(function, pc, "tail call discards live operands");
                    } else {
                        stack.add(Kind.INT);
                        successor(function, states, pending, pc, pc + 1, stack, init);
                    }
                }
                case RET -> {
                    pop(function, pc, stack, Kind.INT);
                    if (!stack.isEmpty())
                        throw reject(function, pc, "return leaves extra operands");
                }
                case TRAP -> {
                    if (!stack.isEmpty())
                        throw reject(function, pc, "trap has live operands");
                }
            }
        }
    }

    private static Kind popAny(ActorScalarCallMachine.Function fn,
                               int pc, ArrayList<Kind> stack) {
        if (stack.isEmpty()) throw reject(fn, pc, "operand stack underflow");
        return stack.removeLast();
    }

    private static void pop(ActorScalarCallMachine.Function fn, int pc,
                            ArrayList<Kind> stack, Kind expected) {
        Kind actual = popAny(fn, pc, stack);
        if (actual != expected) {
            throw reject(fn, pc, "expected " + expected + " but found " + actual);
        }
    }

    private static void successor(
            ActorScalarCallMachine.Function fn, State[] states,
            ArrayDeque<Integer> pending, int from, int to,
            ArrayList<Kind> stack, BitSet initialized) {
        if (to < 0 || to >= states.length)
            throw reject(fn, from, "control flow falls off bytecode image");
        if (stack.size() > fn.maxStack())
            throw reject(fn, from, "operand stack exceeds declared maximum");
        State earlier = states[to];
        if (earlier == null) {
            states[to] = new State(stack, initialized);
            pending.addLast(to);
            return;
        }
        if (!earlier.stack().equals(stack))
            throw reject(fn, to, "incompatible operand depth/types at control-flow join");

        // Definite assignment means initialized on EVERY incoming path.
        BitSet intersection = (BitSet) earlier.initialized().clone();
        intersection.and(initialized);
        if (!intersection.equals(earlier.initialized())) {
            states[to] = new State(stack, intersection);
            pending.addLast(to);
        }
    }

    private static Rejected reject(
            ActorScalarCallMachine.Function fn, int pc, String reason) {
        return new Rejected("invalid scalar bytecode " + fn.name()
                + " at pc=" + pc + ": " + reason);
    }
}
