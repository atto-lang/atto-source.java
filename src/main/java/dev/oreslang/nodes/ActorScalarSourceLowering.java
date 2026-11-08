package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.ActorScalarCallMachine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deliberately closed scalar source compiler for M3.3a.
 *
 * <p>Only statically named pure synchronous `fnc` calls over signed `int`
 * parameters, `int` locals, returns, arithmetic, comparisons and simple
 * if branches are admitted. The entire reachable direct-call graph is
 * checked before ANY guest execution/frame admission. Unknown AST syntax,
 * effects, other type kinds, dynamic/overloaded values, named/type arguments,
 * actor methods, globals and closures force the unchanged legacy evaluator.
 *
 * <p>The resulting program contains no guest AST, JVM closures or object
 * references in its activation frames. It does not claim general compiler
 * parity, actor heap isolation or native physical stack switching.
 */
final class ActorScalarSourceLowering {
    @FunctionalInterface
    interface FunctionResolver {
        Ast.FunctionDecl find(String name, int arity);
    }

    private static final class Unsupported extends RuntimeException {
        private Unsupported() { super(null, null, false, false); }
    }
    private static final Unsupported NO = new Unsupported();
    private static final int MAX_FUNCTIONS = 128;
    private static final int MAX_CODE = 4096;
    private static final int MAX_NESTING = 40;
    private static final int MAX_OPERANDS = 24;

    /** No implicit conversion between the two admitted primitive kinds. */
    private enum ScalarType { INT, BOOL }

    private final FunctionResolver resolver;
    private final IdentityHashMap<Ast.FunctionDecl,Integer> indices = new IdentityHashMap<>();
    private final ArrayList<Ast.FunctionDecl> functions = new ArrayList<>();
    private final ArrayList<ActorScalarCallMachine.Function> compiled = new ArrayList<>();

    private ActorScalarSourceLowering(FunctionResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    static ActorScalarCallMachine.Program tryCompile(
            Ast.FunctionDecl root, FunctionResolver resolver) {
        if (root == null) return null;
        ActorScalarSourceLowering compiler = new ActorScalarSourceLowering(resolver);
        try {
            compiler.enroll(root);
            for (int i = 0; i < compiler.functions.size(); i++) {
                compiler.compiled.add(compiler.lower(compiler.functions.get(i)));
            }
            try {
                return new ActorScalarCallMachine.Program(compiler.compiled);
            } catch (IllegalArgumentException invalid) {
                // The independent CFG verifier found a source-lowering
                // limitation. Do not partially admit the actor machine;
                // preserve the existing evaluator semantics for this graph.
                return null;
            }
        } catch (Unsupported unsupported) {
            // Fail closed without executing ANY part of the unsupported
            // program through the new path; retain old interpreter semantics.
            return null;
        }
    }

    private int enroll(Ast.FunctionDecl function) {
        Integer old = indices.get(function);
        if (old != null) return old;
        if (functions.size() >= MAX_FUNCTIONS || !eligible(function)) throw NO;
        int index = functions.size();
        functions.add(function);
        indices.put(function, index);
        return index;
    }

    private static boolean eligible(Ast.FunctionDecl fn) {
        if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator() || fn.trapped()
                || fn.structural() || fn.nonLexical()
                || !fn.genericParameters().isEmpty() || !fn.genericSpecs().isEmpty()
                || !fn.effects().isEmpty() || !fn.annotations().isEmpty()
                || !isInt(fn.returnType()) || fn.parameters().size() > 16
                || fn.body().isEmpty()) return false;
        for (Ast.Param p : fn.parameters()) {
            if (!isInt(p.type()) || p.mutable() || p.structural()
                    || p.name() == null || p.name().isBlank()) return false;
        }
        return true;
    }

    private static boolean exhaustiveReturns(List<Ast.Stmt> body) {
        if (body.isEmpty()) return false;
        Ast.Stmt terminal = body.getLast();
        if (terminal instanceof Ast.ReturnStmt) return true;
        if (terminal instanceof Ast.IfStmt conditional) {
            if (conditional.branches().isEmpty()
                    || !exhaustiveReturns(conditional.elseBody())) return false;
            for (Ast.IfBranch branch : conditional.branches()) {
                if (!exhaustiveReturns(branch.body())) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isInt(Ast.TypeRef type) {
        return type != null && type.name().equals("int")
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private ActorScalarCallMachine.Function lower(Ast.FunctionDecl fn) {
        Builder builder = new Builder(fn);
        // Admission requires every path to reach an explicit return.
        // A canonical if/else function is exhaustive when every arm and
        // the else body return; a missing else must have a later return.
        if (!exhaustiveReturns(fn.body())) throw NO;
        builder.body(fn.body(), 0, true);
        // Never synthesize a silent zero on malformed control-flow. An
        // unreachable terminal trap makes branch-to-end targets valid,
        // and will poison the actor if compilation invariants are broken.
        builder.emit(ActorScalarCallMachine.Op.TRAP);
        if (builder.instructions.isEmpty() || builder.instructions.size() > MAX_CODE)
            throw NO;
        return new ActorScalarCallMachine.Function(
                fn.name(), fn.parameters().size(), builder.locals.size(),
                Math.max(1, builder.operandPeak), builder.instructions);
    }

    private final class Builder {
        private final LinkedHashMap<String,Integer> locals = new LinkedHashMap<>();
        private final LinkedHashMap<String,Ast.BindingKind> bindingKinds =
                new LinkedHashMap<>();
        private final ArrayList<ActorScalarCallMachine.Instruction> instructions = new ArrayList<>();
        private int operandDepth;
        private int operandPeak;

        private Builder(Ast.FunctionDecl fn) {
            for (Ast.Param p : fn.parameters()) {
                if (locals.putIfAbsent(p.name(), locals.size()) != null) throw NO;
                // Source parameters are immutable unless explicitly declared
                // mutable; this narrow subset excludes mutable parameters.
                bindingKinds.put(p.name(), Ast.BindingKind.CONST);
            }
        }

        private int emit(ActorScalarCallMachine.Instruction instruction) {
            if (instructions.size() >= MAX_CODE) throw NO;
            // Verify the transient operand depth during lowering, before
            // executing *any* guest instruction or allocating an actor frame.
            // A fixed runtime bound without this analysis would poison an
            // otherwise valid actor when deeply nested source expressions
            // exceeded the new subset's temporary stack capacity.
            switch (instruction.op()) {
                case CONST, CONST_BOOL, LOAD -> operandDepth++;
                case STORE, POP, JUMP_IF_FALSE -> operandDepth--;
                case ADD, SUB, MUL, LT, LE, EQ -> operandDepth--;
                case CALL, TAIL_CALL -> {
                    int id = instruction.argument();
                    if (id < 0 || id >= functions.size()) throw NO;
                    operandDepth -= functions.get(id).parameters().size();
                    if (instruction.op() == ActorScalarCallMachine.Op.CALL) {
                        operandDepth++;
                    } else if (operandDepth != 0) {
                        throw NO; // a tail call cannot discard live operands
                    }
                }
                case RET -> {
                    operandDepth--;
                    if (operandDepth != 0) throw NO;
                }
                case JUMP, TRAP -> {
                    if (operandDepth != 0) throw NO;
                }
            }
            if (operandDepth < 0 || operandDepth > MAX_OPERANDS) throw NO;
            operandPeak = Math.max(operandPeak, operandDepth);
            instructions.add(instruction);
            return instructions.size() - 1;
        }

        private int emit(ActorScalarCallMachine.Op operation) {
            return emit(ActorScalarCallMachine.Instruction.of(operation));
        }

        private int branch(ActorScalarCallMachine.Op operation) {
            return emit(ActorScalarCallMachine.Instruction.at(operation, 0));
        }

        private void patch(int at, int target) {
            if (target < 0 || target >= MAX_CODE) throw NO;
            var old = instructions.get(at);
            instructions.set(at, ActorScalarCallMachine.Instruction.at(old.op(), target));
        }

        private void body(List<Ast.Stmt> stmts, int depth, boolean allowBindings) {
            if (depth > MAX_NESTING) throw NO;
            for (Ast.Stmt stmt : stmts) {
                if (stmt instanceof Ast.ReturnStmt ret) {
                    if (ret.value() == null) throw NO;
                    if (ret.value() instanceof Ast.CallExpr direct) {
                        call(direct, depth + 1, true);
                    } else {
                        if (expression(ret.value(), depth + 1) != ScalarType.INT)
                            throw NO;
                        emit(ActorScalarCallMachine.Op.RET);
                    }
                } else if (stmt instanceof Ast.BindingStmt binding) {
                    if (!allowBindings || !isInt(binding.declaredType())
                            || binding.initializer() == null
                            || binding.inferredTypeName() != null
                            || locals.containsKey(binding.name())
                            || locals.size() >= 32) throw NO;
                    if (expression(binding.initializer(), depth + 1) != ScalarType.INT)
                        throw NO;
                    int index = locals.size();
                    locals.put(binding.name(), index);
                    bindingKinds.put(binding.name(), binding.kind());
                    emit(ActorScalarCallMachine.Instruction.at(
                            ActorScalarCallMachine.Op.STORE, index));
                } else if (stmt instanceof Ast.ExprStmt expression) {
                    if (!(expression.expression() instanceof Ast.AssignExpr assign)
                            || !(assign.target() instanceof Ast.NameExpr name)
                            || !locals.containsKey(name.name())
                            || bindingKinds.get(name.name()) != Ast.BindingKind.LET)
                        throw NO;
                    if (expression(assign.value(), depth + 1) != ScalarType.INT)
                        throw NO;
                    emit(ActorScalarCallMachine.Instruction.at(
                            ActorScalarCallMachine.Op.STORE, locals.get(name.name())));
                } else if (stmt instanceof Ast.IfStmt conditional) {
                    ArrayList<Integer> endJumps = new ArrayList<>();
                    for (Ast.IfBranch arm : conditional.branches()) {
                        if (expression(arm.condition(), depth + 1) != ScalarType.BOOL)
                            throw NO;
                        int skip = branch(ActorScalarCallMachine.Op.JUMP_IF_FALSE);
                        body(arm.body(), depth + 1, false);
                        endJumps.add(branch(ActorScalarCallMachine.Op.JUMP));
                        patch(skip, instructions.size());
                    }
                    body(conditional.elseBody(), depth + 1, false);
                    // Branches can validly target the last instruction
                    // only when a real return follows this IfStmt.
                    int after = instructions.size();
                    for (int jump : endJumps) patch(jump, after);
                } else throw NO;
            }
        }

        private ScalarType expression(Ast.Expr e, int depth) {
            if (depth > MAX_NESTING) throw NO;
            if (e instanceof Ast.LiteralExpr literal) {
                Object value = literal.value();
                if (value instanceof Long x) {
                    emit(ActorScalarCallMachine.Instruction.constant(x));
                    return ScalarType.INT;
                } else if (value instanceof Boolean x) {
                    emit(new ActorScalarCallMachine.Instruction(
                            ActorScalarCallMachine.Op.CONST_BOOL, 0, x ? 1 : 0));
                    return ScalarType.BOOL;
                }
                throw NO;
            }
            if (e instanceof Ast.NameExpr name) {
                Integer local = locals.get(name.name());
                if (local == null) throw NO;
                emit(ActorScalarCallMachine.Instruction.at(
                        ActorScalarCallMachine.Op.LOAD, local));
                // M3.3b only admits int parameters and int local bindings.
                return ScalarType.INT;
            }
            if (e instanceof Ast.BinaryExpr binary) {
                ScalarType left = expression(binary.left(), depth + 1);
                ScalarType right = expression(binary.right(), depth + 1);
                if (left != right) throw NO; // no implicit int/bool conversion
                switch (binary.operator()) {
                    case "+", "-", "*", "<", "<=" -> {
                        if (left != ScalarType.INT) throw NO;
                        ActorScalarCallMachine.Op operation = switch (binary.operator()) {
                            case "+" -> ActorScalarCallMachine.Op.ADD;
                            case "-" -> ActorScalarCallMachine.Op.SUB;
                            case "*" -> ActorScalarCallMachine.Op.MUL;
                            case "<" -> ActorScalarCallMachine.Op.LT;
                            default -> ActorScalarCallMachine.Op.LE;
                        };
                        emit(operation);
                        return (operation == ActorScalarCallMachine.Op.LT
                                || operation == ActorScalarCallMachine.Op.LE)
                                ? ScalarType.BOOL : ScalarType.INT;
                    }
                    case "eq", "==" -> {
                        emit(ActorScalarCallMachine.Op.EQ);
                        return ScalarType.BOOL;
                    }
                    default -> throw NO;
                }
            }
            if (e instanceof Ast.UnaryExpr unary && unary.operator().equals("-")) {
                // Preserve left-to-right and signed arithmetic semantics
                // without implicit Boolean-to-integer coercion.
                emit(ActorScalarCallMachine.Instruction.constant(0L));
                if (expression(unary.operand(), depth + 1) != ScalarType.INT)
                    throw NO;
                emit(ActorScalarCallMachine.Op.SUB);
                return ScalarType.INT;
            }
            if (e instanceof Ast.CallExpr call) {
                call(call, depth + 1, false);
                return ScalarType.INT;
            }
            throw NO;
        }

        private void call(Ast.CallExpr call, int depth, boolean tail) {
            if (depth > MAX_NESTING || !(call.callee() instanceof Ast.NameExpr name)
                    || call.origin() != Ast.CallOrigin.DIRECT
                    || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                    || locals.containsKey(name.name())) throw NO;
            Ast.FunctionDecl callee = resolver.find(name.name(), call.arguments().size());
            if (callee == null) throw NO;
            // No partial lowering: all callee declarations are checked
            // transitively before runtime actor-frame admission. Each
            // argument must independently be exactly int, never Boolean.
            int id = enroll(callee);
            for (Ast.Expr arg : call.arguments()) {
                if (expression(arg, depth + 1) != ScalarType.INT) throw NO;
            }
            emit(ActorScalarCallMachine.Instruction.at(
                    tail ? ActorScalarCallMachine.Op.TAIL_CALL
                         : ActorScalarCallMachine.Op.CALL, id));
        }
    }
}
