package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * G1c prototype: a closed, typed source-HIR subset for AttoLang gpu fnc.
 *
 * Not an executable IR or a backend capability: no kernel binary identity,
 * physical GPU provider, device residency, alias/loan proof, bounds proof,
 * barrier convergence, cancellation or completion-fence proof is issued here.
 * Production TypeChecker always rejects GPU kernel execution after this pass.
 */
public final class GpuKernelSourceHirV1 {
    private GpuKernelSourceHirV1() { }

    public enum Type {
        I64, F32, BOOL, VOID, BUFFER_READ_F32, BUFFER_WRITE_F32, BUFFER_READ_WRITE_F32
    }
    public enum Evidence { TYPED_SOURCE_ONLY_NOT_EXECUTABLE }

    public record Parameter(String name, Type type) {
        public Parameter {
            if (name == null || name.isBlank() || name.length() > 256)
                throw fail("001", "invalid GPU kernel parameter name");
            Objects.requireNonNull(type, "GPU parameter type");
        }
    }
    /** Immutable typed value tree, with no generic host callable nodes. */
    public record Value(Type type, String opcode, String symbol, List<Value> operands) {
        public Value {
            Objects.requireNonNull(type, "GPU value type");
            Objects.requireNonNull(opcode, "GPU value opcode");
            operands = List.copyOf(operands);
        }
    }
    /** Statement sequence is purposefully restricted: LET, STORE, IF, RETURN. */
    public record Instruction(String opcode, String target, Value value,
                              List<Instruction> body) {
        public Instruction {
            Objects.requireNonNull(opcode, "GPU statement opcode");
            body = List.copyOf(body);
        }
    }
    public record Plan(String symbol, List<Parameter> parameters, Type returnType,
                       List<Instruction> body, Evidence evidence) {
        public Plan {
            if (symbol == null || symbol.isBlank())
                throw fail("001", "missing GPU entry symbol");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType, "GPU kernel return type");
            body = List.copyOf(body);
            if (evidence != Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE)
                throw fail("008", "missing non-executable source proof state");
        }
        /** Intentional: source typing confers no physical execution authority. */
        public boolean executable() { return false; }
    }

    private static IllegalArgumentException fail(String code, String detail) {
        return new IllegalArgumentException("GPU-KERNEL-" + code + ": " + detail);
    }

    private static Type typeRef(Ast.TypeRef ref) {
        if (ref == null || ref.inferArguments() || ref.isBorrow())
            throw fail("002", "GPU ABI requires explicit, owned parameter types");
        if (ref.arguments().isEmpty()) {
            return switch (ref.name()) {
                case "int", "i64" -> Type.I64;
                case "f32" -> Type.F32;
                case "bool" -> Type.BOOL;
                case "void" -> Type.VOID;
                default -> throw fail("002", "unsupported GPU scalar or ABI type: " + ref.name());
            };
        }
        if (ref.arguments().size() == 1
                && ref.arguments().getFirst().name().equals("f32")
                && ref.arguments().getFirst().arguments().isEmpty()
                && !ref.arguments().getFirst().inferArguments()) {
            return switch (ref.name()) {
                case "GpuReadBuffer" -> Type.BUFFER_READ_F32;
                case "GpuWriteBuffer" -> Type.BUFFER_WRITE_F32;
                case "GpuReadWriteBuffer" -> Type.BUFFER_READ_WRITE_F32;
                default -> throw fail("002", "unsupported GPU buffer qualifier: " + ref.name());
            };
        }
        throw fail("002", "unsupported nested, generic or unsized GPU ABI type");
    }

    private static Value value(Ast.Expr expr, Map<String, Type> locals, int depth) {
        if (depth > 32) throw fail("006", "GPU expression nesting exceeds quota");
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long n)
                return new Value(Type.I64, "i64.literal", Long.toString(n), List.of());
            if (literal.value() instanceof Boolean flag)
                return new Value(Type.BOOL, "bool.literal", flag.toString(), List.of());
            // Java double literals are never silently converted to f32.
            throw fail("002", "unsupported GPU literal; no implicit f64 to f32 conversion");
        }
        if (expr instanceof Ast.NameExpr name) {
            Type kind = locals.get(name.name());
            if (kind == null) throw fail("003", "unbound GPU symbol: " + name.name());
            return new Value(kind, "local", name.name(), List.of());
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.MemberExpr method
                    && method.receiver() instanceof Ast.NameExpr ns
                    && ns.name().equals("gpu")
                    && method.member().equals("global_id")
                    && call.origin() == Ast.CallOrigin.DIRECT
                    && !call.typeArgumentsPresent()
                    && call.arguments().size() == 1
                    && call.arguments().getFirst() instanceof Ast.LiteralExpr literal
                    && literal.value() instanceof Long axis
                    && axis >= 0 && axis <= 2) {
                return new Value(Type.I64, "gpu.global_id",
                        Long.toString(axis), List.of());
            }
            throw fail("006", "host calls, dynamic dispatch and unknown device intrinsics forbidden");
        }
        if (expr instanceof Ast.IndexExpr index) {
            Value receiver = value(index.receiver(), locals, depth + 1);
            if (receiver.type() != Type.BUFFER_READ_F32
                    && receiver.type() != Type.BUFFER_READ_WRITE_F32) {
                throw fail("005", "device load requires readable buffer capability");
            }
            Value offset = value(index.index(), locals, depth + 1);
            require(offset, Type.I64, "device buffer index");
            return new Value(Type.F32, "buffer.load", null,
                    List.of(receiver, offset));
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Value left = value(binary.left(), locals, depth + 1);
            Value right = value(binary.right(), locals, depth + 1);
            if (left.type() != right.type()) {
                throw fail("002", "GPU operands require exact matching types; no implicit conversions");
            }
            String op = binary.operator();
            if (op.equals("+") || op.equals("-") || op.equals("*")) {
                if (left.type() != Type.I64 && left.type() != Type.F32)
                    throw fail("002", "arithmetic requires integer or f32 operands");
                return new Value(left.type(), "arith." + op, null, List.of(left, right));
            }
            if (op.equals("<") || op.equals("<=") || op.equals(">")
                    || op.equals(">=") || op.equals("eq") || op.equals("neq")) {
                if (left.type() != Type.I64 && left.type() != Type.F32)
                    throw fail("002", "comparison requires integer or f32 operands");
                return new Value(Type.BOOL, "compare." + op, null, List.of(left, right));
            }
            if (op.equals("&&") || op.equals("||")) {
                require(left, Type.BOOL, "boolean GPU operation");
                return new Value(Type.BOOL, "logic." + op, null, List.of(left, right));
            }
            throw fail("006", "GPU binary operation not in closed device subset");
        }
        if (expr instanceof Ast.UnaryExpr unary && unary.operator().equals("!")) {
            Value operand = value(unary.operand(), locals, depth + 1);
            require(operand, Type.BOOL, "boolean negation");
            return new Value(Type.BOOL, "logic.not", null, List.of(operand));
        }
        throw fail("006", "suspension, allocation, host effects and unsupported expressions forbidden");
    }

    private static void require(Value expression, Type expected, String purpose) {
        if (expression.type() != expected)
            throw fail("002", purpose + " must be " + expected + ", got " + expression.type());
    }

    private static List<Instruction> body(List<Ast.Stmt> statements,
                                          Map<String, Type> symbols, int depth) {
        if (depth > 16 || statements.size() > 256)
            throw fail("006", "GPU kernel control-flow resource quota exceeded");
        List<Instruction> lowered = new ArrayList<>();
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                if (binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent() || binding.inferredTypeName() != null)
                    throw fail("006", "GPU v1 locals must be immutable scalar bindings");
                Value initializer = value(binding.initializer(), symbols, depth + 1);
                if (initializer.type() != Type.I64
                        && initializer.type() != Type.F32
                        && initializer.type() != Type.BOOL)
                    throw fail("005", "GPU local cannot alias or own a device buffer");
                if (binding.declaredType() != null
                        && !binding.declaredType().name().equals("$infer$")
                        && typeRef(binding.declaredType()) != initializer.type())
                    throw fail("002", "GPU local initializer type mismatch");
                if (symbols.putIfAbsent(binding.name(), initializer.type()) != null)
                    throw fail("003", "GPU local shadows another binding: " + binding.name());
                lowered.add(new Instruction("LET", binding.name(), initializer, List.of()));
            } else if (statement instanceof Ast.ExprStmt expression
                    && expression.expression() instanceof Ast.AssignExpr assign) {
                if (!(assign.target() instanceof Ast.IndexExpr index)
                        || !(index.receiver() instanceof Ast.NameExpr target))
                    throw fail("005", "GPU assignment requires an indexed writable device buffer");
                Type capability = symbols.get(target.name());
                if (capability != Type.BUFFER_WRITE_F32
                        && capability != Type.BUFFER_READ_WRITE_F32)
                    throw fail("005", "GPU store requires exclusive device write capability");
                Value offset = value(index.index(), symbols, depth + 1);
                require(offset, Type.I64, "GPU store index");
                Value rhs = value(assign.value(), symbols, depth + 1);
                require(rhs, Type.F32, "GPU stored value");
                lowered.add(new Instruction("STORE_F32", target.name(),
                        new Value(Type.F32, "store.arguments", null,
                                List.of(offset, rhs)), List.of()));
            } else if (statement instanceof Ast.IfStmt guard) {
                if (guard.branches().size() != 1 || !guard.elseBody().isEmpty())
                    throw fail("006", "GPU v1 supports one branch without else");
                Ast.IfBranch branch = guard.branches().getFirst();
                Value condition = value(branch.condition(), symbols, depth + 1);
                require(condition, Type.BOOL, "GPU if predicate");
                lowered.add(new Instruction("IF", null, condition,
                        body(branch.body(), new LinkedHashMap<>(symbols), depth + 1)));
            } else if (statement instanceof Ast.ReturnStmt ret && ret.value() == null) {
                lowered.add(new Instruction("RETURN_VOID", null, null, List.of()));
            } else {
                throw fail("006", "GPU statements allow only immutable locals, stores, simple if and void return");
            }
        }
        return List.copyOf(lowered);
    }

    /**
     * A structural typed source plan, NOT hardware execution permission.
     * Runtime must not inspect this plan to discover a host callable.
     */
    public static Plan lower(Ast.GpuKernelDecl declaration) {
        Objects.requireNonNull(declaration, "GPU kernel declaration");
        Ast.FunctionDecl fn = declaration.signature();
        if (fn.kind() != Ast.CallableKind.FNC
                || fn.actorKind() != Ast.ActorKind.NONE || fn.async()
                || fn.suspends() || fn.generator() || fn.nonLexical()
                || fn.structural() || fn.trapped() || !fn.effects().isEmpty()
                || !fn.annotations().isEmpty() || !fn.genericParameters().isEmpty()) {
            throw fail("006", "GPU v1 requires a pure, nongeneric, non-suspending ordinary fnc");
        }
        if (typeRef(fn.returnType()) != Type.VOID)
            throw fail("002", "GPU v1 kernels return void; launch returns Future<void>");
        if (fn.parameters().size() > 64)
            throw fail("002", "GPU v1 signature exceeds parameter quota");
        List<Parameter> params = new ArrayList<>();
        LinkedHashMap<String, Type> scope = new LinkedHashMap<>();
        for (Ast.Param param : fn.parameters()) {
            Type kind = typeRef(param.type());
            if (kind == Type.VOID) throw fail("002", "void is not a GPU parameter type");
            if (param.structural()) throw fail("006", "GPU v1 cannot capture structural host values");
            if (param.mutable()
                    && kind != Type.BUFFER_WRITE_F32
                    && kind != Type.BUFFER_READ_WRITE_F32)
                throw fail("005", "mutable GPU parameters require a writable buffer");
            if (scope.putIfAbsent(param.name(), kind) != null)
                throw fail("003", "duplicate GPU parameter: " + param.name());
            params.add(new Parameter(param.name(), kind));
        }
        return new Plan(fn.name(), params, Type.VOID, body(fn.body(), scope, 0),
                Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE);
    }
}
