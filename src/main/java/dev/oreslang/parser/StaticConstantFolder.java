package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresSymbol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed, bounded compile-time scalar constant folding. This is NOT a guest
 * evaluator: calls, allocation, host interop and user-defined operators never
 * execute during parse, link or import.
 *
 * Cross-file constant references and immutable aggregates require separate
 * versioned ABI support and are deliberately rejected for now.
 */
public final class StaticConstantFolder {
    private static final int MAX_CONSTANTS = 4096;
    private static final int MAX_EXPRESSION_STEPS = 10_000;
    private static final int MAX_RECURSION_DEPTH = 64;
    private static final int MAX_STRING_BYTES = 1_048_576;

    private record Key(String module, String name) {
        @Override public String toString() { return module + "." + name; }
    }

    private final Map<String, Ast.ModuleDecl> modules = new LinkedHashMap<>();
    private final Map<Key, Ast.FieldDecl> fields = new LinkedHashMap<>();
    private final Map<Key, Object> memo = new HashMap<>();
    private final Map<Key, Boolean> visiting = new HashMap<>();
    private final ArrayDeque<Key> stack = new ArrayDeque<>();
    private int steps;
    private int expressionDepth;

    private StaticConstantFolder(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null)
                throw new IllegalArgumentException("duplicate module " + module.name());
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FieldDecl field) {
                    Key key = new Key(module.name(), field.name());
                    if (fields.putIfAbsent(key, field) != null)
                        throw new IllegalArgumentException("duplicate module field " + key);
                }
            }
        }
        if (fields.size() > MAX_CONSTANTS)
            throw new IllegalArgumentException("too many file/module bindings for static evaluation");
    }

    /** Fold all static bindings regardless of whether they are imported or used. */
    public static Ast.Program fold(Ast.Program program) {
        StaticConstantFolder folder = new StaticConstantFolder(program);
        List<Ast.ModuleDecl> rewritten = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> declarations = new ArrayList<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FieldDecl field
                        && field.bindingKind() == Ast.BindingKind.CONST) {
                    Object constant = folder.evaluate(new Key(module.name(), field.name()));
                    declarations.add(new Ast.FieldDecl(field.name(), field.visibility(),
                            field.bindingKind(), field.type(), field.annotations(),
                            new Ast.LiteralExpr(constant), field.mutableReferent()));
                } else {
                    declarations.add(declaration);
                }
            }
            rewritten.add(new Ast.ModuleDecl(module.name(), module.visibility(),
                    module.annotations(), module.contracts(), declarations));
        }
        return new Ast.Program(program.namespace(), program.imports(), rewritten);
    }

    private Object evaluate(Key key) {
        if (memo.containsKey(key)) return memo.get(key);
        Ast.FieldDecl field = fields.get(key);
        if (field == null) throw new IllegalArgumentException("unresolved compile-time constant " + key);
        if (field.bindingKind() != Ast.BindingKind.CONST)
            throw new IllegalArgumentException("constant " + key + " refers to non-const runtime binding");
        if (stack.size() >= MAX_RECURSION_DEPTH)
            throw new IllegalArgumentException("constant dependency chain exceeds depth limit at " + key);
        if (visiting.containsKey(key)) {
            List<Key> path = new ArrayList<>(stack);
            java.util.Collections.reverse(path);
            path.add(key);
            throw new IllegalArgumentException("circular constant dependency: " + path);
        }
        visiting.put(key, true);
        stack.push(key);
        try {
            // Validate the entire expression tree, including dead/short-circuited
            // branches, before folding any values. Runtime calls must not be
            // smuggled into a const through 'false && userCall()'.
            requireClosedExpression(field.initializer(), 0);
            Object value = eval(field.initializer(), key.module());
            memo.put(key, value);
            return value;
        } finally {
            stack.pop();
            visiting.remove(key);
        }
    }

    private void requireClosedExpression(Ast.Expr expr, int depth) {
        if (depth >= MAX_RECURSION_DEPTH)
            throw new IllegalArgumentException("compile-time expression exceeds depth limit");
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;
        if (expr instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr) return;
        if (expr instanceof Ast.UnaryExpr unary) {
            requireClosedExpression(unary.operand(), depth + 1);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            requireClosedExpression(binary.left(), depth + 1);
            requireClosedExpression(binary.right(), depth + 1);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr choice) {
            requireClosedExpression(choice.condition(), depth + 1);
            requireClosedExpression(choice.whenTrue(), depth + 1);
            requireClosedExpression(choice.whenFalse(), depth + 1);
            return;
        }
        throw new IllegalArgumentException("file/module const requires a closed compile-time expression; "
                + "guest calls, allocations and runtime expressions are forbidden: "
                + expr.getClass().getSimpleName());
    }

    private Object eval(Ast.Expr expression, String module) {
        if (++steps > MAX_EXPRESSION_STEPS)
            throw new IllegalArgumentException("compile-time constant evaluation step limit exceeded");
        if (++expressionDepth > MAX_RECURSION_DEPTH) {
            expressionDepth--;
            throw new IllegalArgumentException("compile-time evaluation expression depth exceeded");
        }
        try {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null || value instanceof Long || value instanceof Integer
                    || value instanceof Double || value instanceof Boolean
                    || value instanceof OresSymbol) return value;
            if (value instanceof String text) return boundedString(text);
            throw new IllegalArgumentException("unsupported compile-time literal type: "
                    + value.getClass().getSimpleName());
        }
        if (expression instanceof Ast.NameExpr name) {
            Key local = new Key(module, name.name());
            if (fields.containsKey(local)) return evaluate(local);
            Key root = new Key(Parser.ROOT_MODULE, name.name());
            if (!module.equals(Parser.ROOT_MODULE) && fields.containsKey(root))
                return evaluate(root);
            throw new IllegalArgumentException("unknown compile-time constant '" + name.name()
                    + "' in module " + module + "; imported values require static linking");
        }
        if (expression instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr owner) {
            Key target = new Key(owner.name(), member.member());
            if (modules.containsKey(owner.name()) && fields.containsKey(target)) {
                Ast.FieldDecl field = fields.get(target);
                if (!owner.name().equals(module)
                        && field.visibility() != Ast.Visibility.PUBLIC)
                    throw new IllegalArgumentException("private module constant is not accessible: " + target);
                return evaluate(target);
            }
            throw new IllegalArgumentException("unresolved module constant " + target);
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            Object value = eval(unary.operand(), module);
            return switch (unary.operator()) {
                case "-" -> {
                    if (value instanceof Long number) yield Math.negateExact(number);
                    if (value instanceof Double number) yield -number;
                    yield invalid("unary - requires a numeric constant");
                }
                case "+" -> value instanceof Number ? value : invalid("unary + requires a numeric constant");
                case "!" -> value instanceof Boolean flag ? !flag
                        : invalid("unary ! requires a bool constant");
                default -> invalid("unsupported constant unary operator " + unary.operator());
            };
        }
        if (expression instanceof Ast.ConditionalExpr choice) {
            Object value = eval(choice.condition(), module);
            if (!(value instanceof Boolean condition))
                throw new IllegalArgumentException("compile-time conditional requires bool");
            return eval(condition ? choice.whenTrue() : choice.whenFalse(), module);
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            Object left = eval(binary.left(), module);
            if (binary.operator().equals("&&") && left instanceof Boolean a && !a) return false;
            if (binary.operator().equals("||") && left instanceof Boolean a && a) return true;
            Object right = eval(binary.right(), module);
            return binary(binary.operator(), left, right);
        }
        throw new IllegalArgumentException(
                "file/module const requires statically evaluable data; guest calls, allocations "
                        + "and runtime expressions are forbidden: " + expression.getClass().getSimpleName());
        } finally {
            expressionDepth--;
        }
    }

    private Object binary(String op, Object a, Object b) {
        try {
            if (op.equals("==") || op.equals("eq"))
                return constantEquals(a, b);
            if (op.equals("!=") || op.equals("neq"))
                return !constantEquals(a, b);
            if (a instanceof Boolean x && b instanceof Boolean y) {
                return switch (op) {
                    case "&&" -> x && y;
                    case "||" -> x || y;
                    default -> invalid("invalid compile-time bool operator " + op);
                };
            }
            if (a instanceof String x && b instanceof String y && op.equals("+"))
                return boundedString(x + y);
            if (a instanceof Long x && b instanceof Long y) {
                return switch (op) {
                    case "+" -> Math.addExact(x, y);
                    case "-" -> Math.subtractExact(x, y);
                    case "*" -> Math.multiplyExact(x, y);
                    case "/" -> exactIntegerDivision(x, y);
                    case "%" -> x % y;
                    case "<" -> x < y;
                    case "<=" -> x <= y;
                    case ">" -> x > y;
                    case ">=" -> x >= y;
                    default -> invalid("invalid compile-time integer operator " + op);
                };
            }
            if (a instanceof Number x && b instanceof Number y) {
                double left = x.doubleValue(), right = y.doubleValue();
                if (!Double.isFinite(left) || !Double.isFinite(right))
                    return invalid("nonfinite compile-time numeric operand");
                int compare = compareNumbers(x, y);
                double result;
                switch (op) {
                    case "+" -> result = left + right;
                    case "-" -> result = left - right;
                    case "*" -> result = left * right;
                    case "/" -> result = left / right;
                    case "<" -> { return compare < 0; }
                    case "<=" -> { return compare <= 0; }
                    case ">" -> { return compare > 0; }
                    case ">=" -> { return compare >= 0; }
                    default -> { return invalid("invalid compile-time numeric operator " + op); }
                }
                if (!Double.isFinite(result)) return invalid("nonfinite compile-time numeric result");
                return result;
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("overflow or division by zero in constant expression", failure);
        }
        return invalid("incompatible operands in compile-time expression: " + op);
    }

    /**
     * Symbol/String equality is strictly nominal; mixed numeric comparisons
     * use exact integer-vs-float decimal comparison so integers beyond 2^53
     * do not silently collapse onto adjacent floating point numbers.
     */
    private boolean constantEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y)
            return compareNumbers(x, y) == 0;
        return Objects.equals(a, b);
    }

    private int compareNumbers(Number x, Number y) {
        if (x instanceof Long a && y instanceof Long b) return Long.compare(a, b);
        if (x instanceof Integer a && y instanceof Integer b) return Integer.compare(a, b);
        if (x instanceof Double a && y instanceof Double b) {
            if (!Double.isFinite(a) || !Double.isFinite(b))
                throw new IllegalArgumentException("nonfinite constant number");
            return a < b ? -1 : a > b ? 1 : 0;
        }
        BigDecimal left = x instanceof Double a ? BigDecimal.valueOf(a)
                : BigDecimal.valueOf(x.longValue());
        BigDecimal right = y instanceof Double b ? BigDecimal.valueOf(b)
                : BigDecimal.valueOf(y.longValue());
        return left.compareTo(right);
    }

    private long exactIntegerDivision(long x, long y) {
        if (y == 0) throw new ArithmeticException("divide by zero");
        if (x == Long.MIN_VALUE && y == -1) throw new ArithmeticException("signed division overflow");
        return x / y;
    }

    private String boundedString(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES)
            throw new IllegalArgumentException("compile-time String exceeds byte limit");
        return value;
    }

    private static <T> T invalid(String message) {
        throw new IllegalArgumentException(message);
    }
}
