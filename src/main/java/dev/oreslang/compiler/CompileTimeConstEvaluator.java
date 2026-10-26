package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresSymbol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, authority-free evaluator for the closed Const<T> subset.
 *
 * <p>No name lookup, calls, runtime handles, I/O, reflection, allocation
 * authority, actors, tasks, environment, time, randomness or source parsing are
 * available here. The result is ordinary typed AST data that must still pass
 * the normal compiler semantic pipeline.</p>
 */
final class CompileTimeConstEvaluator {
    static final int MAX_STEPS = 512;
    static final int MAX_DEPTH = 64;
    static final int MAX_AGGREGATE_ELEMENTS = 256;
    static final int MAX_STRING_CHARS = 65_536;
    static final long MAX_ESTIMATED_BYTES = 1_048_576L;

    enum FailureKind {
        RUNTIME_DEPENDENT,
        UNSUPPORTED,
        INVALID,
        RESOURCE
    }

    static final class Failure extends IllegalArgumentException {
        private final FailureKind kind;

        Failure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }

    record Evaluation(Ast.Expr expression, Ast.TypeRef type) {
        Evaluation {
            java.util.Objects.requireNonNull(expression, "compile-time const expression");
            java.util.Objects.requireNonNull(type, "compile-time const type");
        }
    }

    private int steps;
    private int aggregateElements;
    private long estimatedBytes;

    private CompileTimeConstEvaluator() { }

    static Evaluation evaluate(Ast.Expr expression) {
        if (expression == null) {
            throw new Failure(FailureKind.INVALID, "compile-time constant expression cannot be null");
        }
        return new CompileTimeConstEvaluator().evaluate(expression, 0);
    }

    static boolean typeMatches(Ast.TypeRef expected, Ast.TypeRef actual) {
        if (expected == null || actual == null || expected.inferArguments() || actual.inferArguments()) {
            return false;
        }
        String expectedName = normalizeTypeName(expected.name());
        String actualName = normalizeTypeName(actual.name());
        if (!expectedName.equals(actualName)
                || expected.arguments().size() != actual.arguments().size()) {
            return false;
        }
        for (int i = 0; i < expected.arguments().size(); i++) {
            if (!typeMatches(expected.arguments().get(i), actual.arguments().get(i))) return false;
        }
        return true;
    }

    static String displayType(Ast.TypeRef type) {
        if (type.arguments().isEmpty()) return normalizeTypeName(type.name());
        if (type.isTupleType()) {
            return "(" + type.arguments().stream()
                    .map(CompileTimeConstEvaluator::displayType)
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")";
        }
        return normalizeTypeName(type.name()) + "<"
                + type.arguments().stream()
                .map(CompileTimeConstEvaluator::displayType)
                .reduce((a, b) -> a + ", " + b).orElse("") + ">";
    }

    private static String normalizeTypeName(String name) {
        if (name.equals("boolean")) return "bool";
        if (name.equals("string")) return "String";
        return name;
    }

    private Evaluation evaluate(Ast.Expr expression, int depth) {
        charge(depth);

        if (expression instanceof Ast.LiteralExpr literal) {
            return literal(literal);
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return unary(unary, depth);
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return binary(binary, depth);
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            Evaluation condition = evaluate(conditional.condition(), depth + 1);
            boolean selected = requireBoolean(condition, "compile-time conditional");
            Evaluation whenTrue = evaluate(conditional.whenTrue(), depth + 1);
            Evaluation whenFalse = evaluate(conditional.whenFalse(), depth + 1);
            if (!typeMatches(whenTrue.type(), whenFalse.type())) {
                throw new Failure(
                        FailureKind.INVALID,
                        "compile-time conditional branches must have the same closed type");
            }
            return selected ? whenTrue : whenFalse;
        }
        if (expression instanceof Ast.ListExpr list) {
            return list(list, depth);
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            return tuple(tuple, depth);
        }
        if (expression instanceof Ast.ObjectExpr object) {
            return object(object, depth);
        }

        if (expression instanceof Ast.NameExpr
                || expression instanceof Ast.CallExpr
                || expression instanceof Ast.RuntimeCallExpr
                || expression instanceof Ast.MemberExpr
                || expression instanceof Ast.IndexExpr
                || expression instanceof Ast.AssignExpr
                || expression instanceof Ast.NewExpr
                || expression instanceof Ast.AwaitExpr
                || expression instanceof Ast.TaskExpr
                || expression instanceof Ast.TrapExpr
                || expression instanceof Ast.ChannelOpExpr
                || expression instanceof Ast.DynamicSelectExpr
                || expression instanceof Ast.LambdaExpr
                || expression instanceof Ast.MacroInvocationExpr) {
            throw new Failure(
                    FailureKind.RUNTIME_DEPENDENT,
                    "expression requires runtime/name/call authority");
        }

        throw new Failure(
                FailureKind.UNSUPPORTED,
                "unsupported compile-time constant AST node "
                        + expression.getClass().getSimpleName());
    }

    private Evaluation literal(Ast.LiteralExpr literal) {
        Object value = literal.value();
        if (value instanceof Long) {
            return scalar(value, "int");
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw invalid("non-finite compile-time float is unsupported");
            return scalar(number, "float");
        }
        if (value instanceof Boolean) {
            return scalar(value, "bool");
        }
        if (value instanceof String text) {
            checkStringBudget(text.length());
            return scalar(text, "String");
        }
        if (value instanceof OresSymbol) {
            return scalar(value, "Symbol");
        }
        throw new Failure(
                FailureKind.UNSUPPORTED,
                "literal type is not admitted by the deterministic Const<T> evaluator");
    }

    private Evaluation unary(Ast.UnaryExpr unary, int depth) {
        Evaluation operand = evaluate(unary.operand(), depth + 1);
        Object value = scalarValue(operand, "unary " + unary.operator());
        return switch (unary.operator()) {
            case "+" -> {
                requireNumber(value, "unary +");
                yield operand;
            }
            case "-" -> {
                if (value instanceof Long integer) {
                    try {
                        yield scalar(Math.negateExact(integer), "int");
                    } catch (ArithmeticException overflow) {
                        throw invalid("compile-time integer negation overflow");
                    }
                }
                if (value instanceof Double floating) yield scalar(-floating, "float");
                throw invalid("unary - requires a number");
            }
            case "!" -> scalar(!requireBoolean(operand, "unary !"), "bool");
            case "~" -> scalar(~requireLong(value, "unary ~"), "int");
            default -> throw new Failure(
                    FailureKind.UNSUPPORTED,
                    "operator '" + unary.operator() + "' is not admitted in Const<T> evaluation");
        };
    }

    private Evaluation binary(Ast.BinaryExpr binary, int depth) {
        String operator = binary.operator();

        // Both sides are still evaluated because Const<T> admission requires the
        // entire expression tree to be closed and authority-free, even though
        // runtime boolean operators short-circuit.
        Evaluation left = evaluate(binary.left(), depth + 1);
        Evaluation right = evaluate(binary.right(), depth + 1);

        if (operator.equals("&&") || operator.equals("||") || operator.equals("^^")) {
            boolean a = requireBoolean(left, operator);
            boolean b = requireBoolean(right, operator);
            return scalar(switch (operator) {
                case "&&" -> a && b;
                case "||" -> a || b;
                default -> a ^ b;
            }, "bool");
        }

        Object a = scalarValue(left, "binary " + operator);
        Object b = scalarValue(right, "binary " + operator);

        return switch (operator) {
            case "+" -> add(a, b);
            case "-", "*", "/", "%" -> numeric(operator, a, b);
            case "eq", "==" -> scalar(valueEquals(a, b), "bool");
            case "neq", "!=" -> scalar(!valueEquals(a, b), "bool");
            case "<", "<=", ">", ">=" -> compare(operator, a, b);
            case "&" -> scalar(requireLong(a, "&") & requireLong(b, "&"), "int");
            case "|" -> {
                if (a instanceof Boolean leftBool && b instanceof Boolean rightBool) {
                    yield scalar(leftBool || rightBool, "bool");
                }
                yield scalar(requireLong(a, "|") | requireLong(b, "|"), "int");
            }
            case "^" -> scalar(requireLong(a, "^") ^ requireLong(b, "^"), "int");
            case "<<" -> scalar(requireLong(a, "<<") << shiftDistance(b), "int");
            case ">>" -> scalar(requireLong(a, ">>") >> shiftDistance(b), "int");
            case ">>>" -> scalar(requireLong(a, ">>>") >>> shiftDistance(b), "int");
            default -> throw new Failure(
                    FailureKind.UNSUPPORTED,
                    "operator '" + operator + "' is not admitted in Const<T> evaluation");
        };
    }

    private Evaluation add(Object left, Object right) {
        if (left instanceof String a && right instanceof String b) {
            checkStringBudget((long) a.length() + b.length());
            return scalar(a + b, "String");
        }
        return numeric("+", left, right);
    }

    private Evaluation numeric(String operator, Object left, Object right) {
        if (!(left instanceof Number a) || !(right instanceof Number b)) {
            throw invalid("numeric operator requires numbers");
        }

        boolean integral = a instanceof Long && b instanceof Long && !operator.equals("/");
        if (integral) {
            long x = a.longValue();
            long y = b.longValue();
            if (operator.equals("%") && y == 0) {
                throw invalid("compile-time remainder by zero");
            }
            try {
                return scalar(switch (operator) {
                    case "+" -> Math.addExact(x, y);
                    case "-" -> Math.subtractExact(x, y);
                    case "*" -> Math.multiplyExact(x, y);
                    case "%" -> x % y;
                    default -> throw new AssertionError(operator);
                }, "int");
            } catch (ArithmeticException overflow) {
                throw invalid("compile-time integer " + operator + " overflow");
            }
        }

        double x = a.doubleValue();
        double y = b.doubleValue();
        double value = switch (operator) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> x / y;
            case "%" -> x % y;
            default -> throw new AssertionError(operator);
        };
        if (!Double.isFinite(value)) {
            throw invalid("non-finite compile-time arithmetic result is unsupported");
        }
        return scalar(value, "float");
    }

    private Evaluation compare(String operator, Object left, Object right) {
        int comparison;
        if (left instanceof Number a && right instanceof Number b) {
            // IEEE NaN is unordered, never less/greater/equal in a relational test.
            if (Double.isNaN(a.doubleValue()) || Double.isNaN(b.doubleValue())) {
                return scalar(false, "bool");
            }
            comparison = compareNumbers(a, b);
        } else if (left instanceof String a && right instanceof String b) {
            comparison = a.compareTo(b);
        } else {
            throw invalid("comparison requires two numbers or two strings");
        }

        return scalar(switch (operator) {
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new AssertionError(operator);
        }, "bool");
    }

    private Evaluation list(Ast.ListExpr list, int depth) {
        addAggregateElements(list.elements().size());
        if (list.elements().isEmpty()) {
            throw new Failure(
                    FailureKind.UNSUPPORTED,
                    "empty list Const<T> requires expected-type-directed evaluation");
        }

        List<Ast.Expr> values = new ArrayList<>(list.elements().size());
        Ast.TypeRef elementType = null;
        for (Ast.Expr element : list.elements()) {
            Evaluation evaluated = evaluate(element, depth + 1);
            if (elementType == null) elementType = evaluated.type();
            else if (!typeMatches(elementType, evaluated.type())) {
                throw invalid("compile-time list elements must have one exact closed type");
            }
            values.add(evaluated.expression());
        }
        accountBytes(32L + values.size() * 8L);
        return new Evaluation(
                new Ast.ListExpr(values),
                new Ast.TypeRef("List", List.of(elementType), false));
    }

    private Evaluation tuple(Ast.TupleExpr tuple, int depth) {
        addAggregateElements(tuple.elements().size());
        List<Ast.Expr> values = new ArrayList<>(tuple.elements().size());
        List<Ast.TypeRef> types = new ArrayList<>(tuple.elements().size());
        for (Ast.Expr element : tuple.elements()) {
            Evaluation evaluated = evaluate(element, depth + 1);
            values.add(evaluated.expression());
            types.add(evaluated.type());
        }
        accountBytes(32L + values.size() * 8L);
        return new Evaluation(
                new Ast.TupleExpr(values),
                Ast.TypeRef.tupleType(types));
    }

    private Evaluation object(Ast.ObjectExpr object, int depth) {
        if (object.kind() == Ast.ObjectKind.EXPLICIT_STRUCT) {
            throw new Failure(
                    FailureKind.UNSUPPORTED,
                    "explicit struct Const<T> requires declared-field initialization proof");
        }
        addAggregateElements(object.fields().size());

        List<Ast.ObjectField> fields = new ArrayList<>(object.fields().size());
        Map<String, Ast.TypeRef> types = new LinkedHashMap<>();
        for (Ast.ObjectField field : object.fields()) {
            Evaluation evaluated = evaluate(field.value(), depth + 1);
            fields.add(Ast.ObjectField.named(field.name(), evaluated.expression()));
            types.put(field.name(), evaluated.type());
        }

        long objectBytes = 48L + fields.size() * 16L;
        for (Ast.ObjectField field : fields) {
            objectBytes += field.name().getBytes(StandardCharsets.UTF_8).length;
        }
        accountBytes(objectBytes);
        Ast.TypeRef type = object.structuralRecord()
                ? Ast.TypeRef.readonlyRecordType(types)
                : Ast.TypeRef.structType(types);
        return new Evaluation(
                new Ast.ObjectExpr(fields, object.kind(), object.declaredFields()),
                type);
    }

    private Evaluation scalar(Object value, String type) {
        if (value instanceof Double floating && !Double.isFinite(floating)) {
            throw new Failure(
                    FailureKind.UNSUPPORTED,
                    "non-finite compile-time float is not admitted by the "
                            + "cross-backend deterministic Const<T> profile");
        }
        long bytes = 24L;
        if (value instanceof Boolean) bytes = 16L;
        else if (value instanceof String string) {
            bytes += string.getBytes(StandardCharsets.UTF_8).length;
        } else if (value instanceof OresSymbol symbol) {
            bytes += symbol.name().getBytes(StandardCharsets.UTF_8).length;
        }
        accountBytes(bytes);
        return new Evaluation(new Ast.LiteralExpr(value), Ast.TypeRef.simple(type));
    }

    private Object scalarValue(Evaluation evaluation, String operation) {
        if (evaluation.expression() instanceof Ast.LiteralExpr literal) {
            return literal.value();
        }
        throw new Failure(
                FailureKind.UNSUPPORTED,
                operation + " currently requires scalar Const<T> operands");
    }

    private boolean requireBoolean(Evaluation evaluation, String operation) {
        Object value = scalarValue(evaluation, operation);
        if (value instanceof Boolean bool) return bool;
        throw invalid(operation + " requires bool operands");
    }

    private void requireNumber(Object value, String operation) {
        if (!(value instanceof Long) && !(value instanceof Double)) {
            throw invalid(operation + " requires numeric operands");
        }
    }

    private long requireLong(Object value, String operation) {
        if (value instanceof Long integer) return integer;
        throw invalid(operation + " requires integer operands");
    }

    private int shiftDistance(Object value) {
        long distance = requireLong(value, "shift");
        if (distance < 0 || distance > 63) {
            throw invalid("shift distance must be between 0 and 63");
        }
        return (int) distance;
    }

    private boolean valueEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return numericValueEquals(a, b);
        }
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left instanceof OresSymbol || right instanceof OresSymbol) {
            return left instanceof OresSymbol
                    && right instanceof OresSymbol
                    && left.equals(right);
        }
        if (left instanceof String && right instanceof String
                || left instanceof Boolean && right instanceof Boolean) {
            return left.equals(right);
        }
        throw new Failure(
                FailureKind.UNSUPPORTED,
                "compile-time equality currently admits scalar values only");
    }

    private boolean numericValueEquals(Number left, Number right) {
        boolean integralLeft = left instanceof Long;
        boolean integralRight = right instanceof Long;
        if (integralLeft && integralRight) return left.longValue() == right.longValue();
        if (integralLeft != integralRight) {
            Number integer = integralLeft ? left : right;
            Number floating = integralLeft ? right : left;
            if (!Double.isFinite(floating.doubleValue())) return false;
            return BigDecimal.valueOf(integer.longValue())
                    .compareTo(BigDecimal.valueOf(floating.doubleValue())) == 0;
        }
        return left.doubleValue() == right.doubleValue();
    }

    private int compareNumbers(Number left, Number right) {
        if (left instanceof Long && right instanceof Long) {
            return Long.compare(left.longValue(), right.longValue());
        }
        // Double.compare distinguishes -0.0 from +0.0, but language numeric
        // comparisons use IEEE value ordering where these values are equal.
        if (left instanceof Double && right instanceof Double) {
            double a = left.doubleValue();
            double b = right.doubleValue();
            return a < b ? -1 : a > b ? 1 : 0;
        }
        Number integer = left instanceof Long ? left : right;
        Number floating = left instanceof Long ? right : left;
        double fp = floating.doubleValue();
        int cmp = Double.isInfinite(fp)
                ? (fp > 0 ? -1 : 1)
                : BigDecimal.valueOf(integer.longValue())
                    .compareTo(BigDecimal.valueOf(fp));
        return left instanceof Long ? cmp : -cmp;
    }

    private void checkStringBudget(long chars) {
        if (chars > MAX_STRING_CHARS) {
            throw new Failure(FailureKind.RESOURCE,
                    "compile-time string length exceeds " + MAX_STRING_CHARS);
        }
    }

    private Failure invalid(String message) {
        return new Failure(FailureKind.INVALID, message);
    }

    private void charge(int depth) {
        if (depth > MAX_DEPTH) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant depth exceeds " + MAX_DEPTH);
        }
        if (++steps > MAX_STEPS) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant step budget exceeds " + MAX_STEPS);
        }
    }

    private void addAggregateElements(int count) {
        try {
            aggregateElements = Math.addExact(aggregateElements, count);
        } catch (ArithmeticException overflow) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant aggregate element accounting overflow");
        }
        if (aggregateElements > MAX_AGGREGATE_ELEMENTS) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant aggregate element budget exceeds "
                            + MAX_AGGREGATE_ELEMENTS);
        }
    }

    private void accountBytes(long bytes) {
        try {
            estimatedBytes = Math.addExact(estimatedBytes, Math.max(0L, bytes));
        } catch (ArithmeticException overflow) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant memory accounting overflow");
        }
        if (estimatedBytes > MAX_ESTIMATED_BYTES) {
            throw new Failure(
                    FailureKind.RESOURCE,
                    "compile-time constant estimated memory budget exceeds "
                            + MAX_ESTIMATED_BYTES + " bytes");
        }
    }
}
