package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage-one source-method lowering: a small, statically verified, pure,
 * non-suspending f64 expression kernel over one columnar row.
 *
 * Do not interpret arbitrary AST nodes or turn unsupported Oreslang methods
 * into host Java calls. Every admitted expression is compiled once to a
 * closed expression tree; invocation reads and commits under the owning
 * store's single row mutation lock.
 */
final class InvertedRowMethod {
    private static final int MAX_ASSIGNMENTS = 128;
    private static final int MAX_EXPRESSION_DEPTH = 64;
    private static final int MAX_PARAMETERS = 32;
    private static final int MAX_EXPRESSION_NODES = 4096;

    @FunctionalInterface
    private interface ScalarExpr {
        double read(Object[] row, double[] arguments);
    }
    private record Assignment(int target, ScalarExpr value) { }

    private final String name;
    private final int arity;
    private final List<Assignment> statements;

    private InvertedRowMethod(String name, int arity, List<Assignment> statements) {
        this.name = name;
        this.arity = arity;
        this.statements = List.copyOf(statements);
    }

    String selector() { return name + "/" + arity; }

    static InvertedRowMethod compile(Ast.MethodDecl method, InvertedArray.Schema schema) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(schema, "schema");
        if (method.isStatic() || method.isAbstract() || method.async() || method.suspends()
                || method.structural() || method.explicitReceiverType() != null
                || !method.genericParameters().isEmpty() || !method.annotations().isEmpty()
                || method.returnType() == null || !method.returnType().name().equals("void")
                || method.returnType().inferArguments()
                || !method.returnType().arguments().isEmpty()
                || method.parameters().size() > MAX_PARAMETERS
                || method.body().isEmpty() || method.body().size() > MAX_ASSIGNMENTS) {
            throw new IllegalArgumentException("inverted method '" + method.name()
                    + "' requires a non-suspending, nongeneric, void scalar body");
        }
        Map<String, Integer> parameters = new HashMap<>();
        for (int i = 0; i < method.parameters().size(); i++) {
            Ast.Param parameter = method.parameters().get(i);
            if (parameter.type() == null || !parameter.type().name().equals("f64")
                    || parameter.type().inferArguments()
                    || !parameter.type().arguments().isEmpty()
                    || parameter.structural() || parameter.mutable()
                    || parameters.putIfAbsent(parameter.name(), i) != null
                    || parameter.name().equals("self")) {
                throw new IllegalArgumentException("inverted method parameters must be unique immutable f64 scalars");
            }
        }
        List<Assignment> result = new ArrayList<>();
        int[] remainingNodes = {MAX_EXPRESSION_NODES};
        for (Ast.Stmt statement : method.body()) {
            if (!(statement instanceof Ast.ExprStmt expr)
                    || !(expr.expression() instanceof Ast.AssignExpr assignment)) {
                throw new IllegalArgumentException("inverted method supports only scalar field assignments");
            }
            int target = checkedField(assignment.target(), schema);
            ScalarExpr rhs = lower(assignment.value(), schema, parameters, 0, remainingNodes);
            result.add(new Assignment(target, rhs));
        }
        return new InvertedRowMethod(method.name(), method.parameters().size(), result);
    }

    private static int checkedField(Ast.Expr expression, InvertedArray.Schema schema) {
        if (!(expression instanceof Ast.MemberExpr field)
                || !(field.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals("self")) {
            throw new IllegalArgumentException("inverted method may assign only self.<scalar>");
        }
        int index = schema.index(field.member());
        if (schema.fields().get(index).kind() != InvertedColumnStore.Kind.F64) {
            throw new IllegalArgumentException("stage-one inverted method requires f64 target columns");
        }
        return index;
    }

    private static ScalarExpr lower(
            Ast.Expr value, InvertedArray.Schema schema, Map<String, Integer> parameters,
            int depth, int[] remainingNodes) {
        if (--remainingNodes[0] < 0) {
            throw new IllegalArgumentException("inverted scalar expression budget exceeded");
        }
        if (depth > MAX_EXPRESSION_DEPTH) {
            throw new IllegalArgumentException("inverted method expression nesting exceeds limit");
        }
        // No implicit int/decimal -> f64 cast until source type promotion and
        // overflow/precision semantics are proven by the semantic checker.
        if (value instanceof Ast.LiteralExpr literal && literal.value() instanceof Double fixed) {
            return (row, args) -> fixed;
        }
        if (value instanceof Ast.NameExpr name) {
            Integer at = parameters.get(name.name());
            if (at == null) throw new IllegalArgumentException(
                    "unbound variable '" + name.name() + "' in inverted method");
            return (row, args) -> args[at];
        }
        if (value instanceof Ast.MemberExpr member) {
            int field = checkedField(member, schema);
            return (row, args) -> (Double) row[field];
        }
        if (value instanceof Ast.UnaryExpr unary && unary.operator().equals("-")) {
            ScalarExpr operand = lower(unary.operand(), schema, parameters, depth + 1, remainingNodes);
            return (row, args) -> -operand.read(row, args);
        }
        if (value instanceof Ast.BinaryExpr binary) {
            ScalarExpr left = lower(binary.left(), schema, parameters, depth + 1, remainingNodes);
            ScalarExpr right = lower(binary.right(), schema, parameters, depth + 1, remainingNodes);
            return switch (binary.operator()) {
                case "+" -> (row, args) -> left.read(row, args) + right.read(row, args);
                case "-" -> (row, args) -> left.read(row, args) - right.read(row, args);
                case "*" -> (row, args) -> left.read(row, args) * right.read(row, args);
                default -> throw new IllegalArgumentException(
                        "unsupported inverted scalar operator '" + binary.operator() + "'");
            };
        }
        throw new IllegalArgumentException(
                "inverted method requires pure scalar literals, parameters and self fields");
    }

    void invoke(InvertedColumnStore store, InvertedColumnStore.Handle handle, Object... rawArguments) {
        if (rawArguments == null || rawArguments.length != arity) {
            throw new IllegalArgumentException(
                    "inverted method '" + name + "' requires " + arity + " arguments");
        }
        double[] arguments = new double[arity];
        for (int i = 0; i < arity; i++) {
            if (!(rawArguments[i] instanceof Double value)) {
                throw new IllegalArgumentException(
                        "inverted method arguments must be exact f64 values");
            }
            arguments[i] = value;
        }
        store.transformRow(handle, row -> {
            for (Assignment statement : statements) {
                row[statement.target()] = statement.value().read(row, arguments);
            }
            return row;
        });
    }
}
