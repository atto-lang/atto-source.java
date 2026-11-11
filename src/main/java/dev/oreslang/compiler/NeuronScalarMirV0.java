package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed, structured FLOAT-only neuron MIR candidate built from the real AST.
 *
 * Descriptive AOT compiler artifact, not executable bytecode, a loan receipt,
 * or a bypass of NEURON_STATE_MIR_UNSUPPORTED. Each GuardedScope carries a
 * mandatory TRY_FINALLY release edge; an emitter must ensure a physical loan
 * is released on normal return, cancellation, traps, and failed field access.
 * There is no arbitrary callback, reflection, virtual method or dynamic field.
 */
public final class NeuronScalarMirV0 {
    private NeuronScalarMirV0() { }

    public enum Opcode {
        CONST_F64, LOAD_STATE_F64, LOAD_INPUT_F64,
        NEG_F64, ADD_F64, SUB_F64, MUL_F64, STORE_STATE_F64
    }

    public enum GuardMode { CRITICAL_READ, CRITICAL_WRITE }
    public enum ExitPolicy { RELEASE_IN_FINALLY }

    /** One statically typed stack-machine operation. No guest object values. */
    public record Instruction(Opcode opcode, String symbol, double literal) {
        public Instruction {
            Objects.requireNonNull(opcode, "opcode");
            switch (opcode) {
                case LOAD_STATE_F64, LOAD_INPUT_F64, STORE_STATE_F64 -> {
                    if (symbol == null || symbol.isBlank())
                        throw new IllegalArgumentException("scalar field/input symbol required");
                }
                default -> {
                    if (symbol != null)
                        throw new IllegalArgumentException("unexpected scalar symbol");
                }
            }
            if (opcode != Opcode.CONST_F64 && literal != 0.0)
                throw new IllegalArgumentException("nonconstant opcode cannot carry literal data");
        }
        public static Instruction scalar(double value) {
            return new Instruction(Opcode.CONST_F64, null, value);
        }
        public static Instruction symbol(Opcode op, String name) {
            return new Instruction(op, name, 0.0);
        }
        public static Instruction arithmetic(Opcode op) {
            return new Instruction(op, null, 0.0);
        }
    }

    /**
     * The body is a pure primitive sequence under ONE critical loan. The
     * release is STRUCTURED metadata, not an optional tail instruction that
     * could be skipped by an exceptional exit.
     */
    public record GuardedScope(GuardMode mode, List<Instruction> body,
                               ExitPolicy exit, boolean returnsScalar) {
        public GuardedScope {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(exit, "exit");
            if (exit != ExitPolicy.RELEASE_IN_FINALLY)
                throw new IllegalArgumentException("neuron loan requires finally release");
            body = List.copyOf(body);
            if (body.isEmpty() || body.size() > 1_024)
                throw new IllegalArgumentException("neuron scalar MIR instruction budget");
            if (returnsScalar != (mode == GuardMode.CRITICAL_READ))
                throw new IllegalArgumentException("critical read returns scalar; write returns void");
            for (Instruction instruction : body) {
                if (mode == GuardMode.CRITICAL_READ
                        && instruction.opcode() == Opcode.STORE_STATE_F64)
                    throw new IllegalArgumentException("critical read cannot write state");
            }
        }
    }

    public record Plan(String actor, NeuronStateLayoutV0 layout,
                       GuardedScope initialization, GuardedScope forward) {
        public Plan {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(initialization, "initialization");
            Objects.requireNonNull(forward, "forward");
            if (!actor.equals(layout.actorName())
                    || initialization.mode() != GuardMode.CRITICAL_WRITE
                    || forward.mode() != GuardMode.CRITICAL_READ) {
                throw new IllegalArgumentException("mismatched neuron MIR actor or guard modes");
            }
        }
    }

    private static IllegalArgumentException unsupported(String message) {
        return new IllegalArgumentException("NEURON_SCALAR_MIR_UNSUPPORTED: " + message);
    }

    /** Build a closed float-only stack IR, without enabling guest execution. */
    public static Plan lower(Ast.ClassDecl neuron) {
        Objects.requireNonNull(neuron, "neuron");
        var preflight = NeuronScalarScopeV0.preflight(neuron);
        if (!preflight.hasCriticalScopes())
            throw unsupported("on_start/forward critical scopes are missing");
        var layout = NeuronStateLayoutV0.fromActor(neuron);
        Ast.MethodDecl init = find(neuron, "on_start");
        Ast.MethodDecl forward = find(neuron, "forward");

        Ast.LambdaExpr write = extract(init.body().getFirst(), GuardMode.CRITICAL_WRITE);
        Ast.LambdaExpr read = extract(forward.body().getFirst(), GuardMode.CRITICAL_READ);
        var initOps = new ArrayList<Instruction>();
        String writeAlias = write.parameters().getFirst().name();
        for (Ast.Stmt statement : write.blockBody()) {
            if (!(statement instanceof Ast.ExprStmt exprStmt)
                    || !(exprStmt.expression() instanceof Ast.AssignExpr assignment)
                    || !(assignment.target() instanceof Ast.MemberExpr target)) {
                throw unsupported("scalar initialization requires direct assignments");
            }
            lowerExpression(assignment.value(), writeAlias, null, layout, initOps, 0);
            initOps.add(Instruction.symbol(Opcode.STORE_STATE_F64, target.member()));
        }
        var forwardOps = new ArrayList<Instruction>();
        lowerExpression(read.expressionBody(), read.parameters().getFirst().name(),
                forward.parameters().getFirst().name(), layout, forwardOps, 0);

        return new Plan(neuron.name(), layout,
                new GuardedScope(GuardMode.CRITICAL_WRITE, initOps,
                        ExitPolicy.RELEASE_IN_FINALLY, false),
                new GuardedScope(GuardMode.CRITICAL_READ, forwardOps,
                        ExitPolicy.RELEASE_IN_FINALLY, true));
    }

    private static Ast.MethodDecl find(Ast.ClassDecl actor, String name) {
        Ast.MethodDecl found = null;
        for (Ast.MethodDecl method : actor.methods()) {
            if (method.name().equals(name)) {
                if (found != null) throw unsupported("duplicate method: " + name);
                found = method;
            }
        }
        if (found == null) throw unsupported("missing method: " + name);
        return found;
    }

    private static Ast.LambdaExpr extract(Ast.Stmt statement, GuardMode mode) {
        Ast.Expr expr;
        if (mode == GuardMode.CRITICAL_WRITE && statement instanceof Ast.ExprStmt stmt)
            expr = stmt.expression();
        else if (mode == GuardMode.CRITICAL_READ && statement instanceof Ast.ReturnStmt ret)
            expr = ret.value();
        else throw unsupported("expected guarded critical scope");
        if (!(expr instanceof Ast.CallExpr call)
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.RuntimeCallExpr intr)
                || !intr.operation().equals(
                    mode == GuardMode.CRITICAL_WRITE
                            ? "$neuron.critical.write" : "$neuron.critical.read")
                || intr.arguments().size() != 1
                || !(intr.arguments().getFirst() instanceof Ast.LambdaExpr lambda)) {
            throw unsupported("missing typed critical loan marker");
        }
        return lambda;
    }

    private static void lowerExpression(Ast.Expr expression,
                                        String stateAlias, String inputName,
                                        NeuronStateLayoutV0 layout,
                                        List<Instruction> output, int depth) {
        if (depth > 64) throw unsupported("expression nesting exceeds scalar budget");
        if (expression instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Double value) {
            output.add(Instruction.scalar(value));
        } else if (expression instanceof Ast.NameExpr name
                && inputName != null && name.name().equals(inputName)) {
            output.add(Instruction.symbol(Opcode.LOAD_INPUT_F64, inputName));
        } else if (expression instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr owner
                && owner.name().equals(stateAlias)
                && layout.floatFields().contains(member.member())) {
            output.add(Instruction.symbol(Opcode.LOAD_STATE_F64, member.member()));
        } else if (expression instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("-") || unary.operator().equals("+"))) {
            lowerExpression(unary.operand(), stateAlias, inputName, layout, output, depth + 1);
            if (unary.operator().equals("-"))
                output.add(Instruction.arithmetic(Opcode.NEG_F64));
        } else if (expression instanceof Ast.BinaryExpr binary
                && (binary.operator().equals("+") || binary.operator().equals("-")
                    || binary.operator().equals("*"))) {
            lowerExpression(binary.left(), stateAlias, inputName, layout, output, depth + 1);
            lowerExpression(binary.right(), stateAlias, inputName, layout, output, depth + 1);
            output.add(Instruction.arithmetic(switch (binary.operator()) {
                case "+" -> Opcode.ADD_F64;
                case "-" -> Opcode.SUB_F64;
                case "*" -> Opcode.MUL_F64;
                default -> throw unsupported("unknown scalar operator");
            }));
        } else {
            throw unsupported("effectful or escaping expression cannot lower to scalar MIR");
        }
        if (output.size() > 1_024)
            throw unsupported("scalar MIR expression instruction budget exceeded");
    }
}
