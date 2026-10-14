package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresSymbol;

/**
 * Closed, deterministic AST evaluator used only for explicit final-const
 * declarations. Do not call Oreslang routines, consult module initializers,
 * resolve host objects or permit user-defined operator dispatch here.
 *
 * This is deliberately a strict subset of Oreslang expressions. Unsupported
 * expressions remain invalid rather than executing during import/compilation.
 */
public final class CompileTimeConstantEvaluator {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 1024;
    private static final int MAX_STRING_LENGTH = 65_536;

    private CompileTimeConstantEvaluator() { }

    private static final class Budget {
        int nodes;
    }

    public static Object evaluate(Ast.Expr expression) {
        if (expression == null) {
            throw reject("initializer is required");
        }
        return evaluate(expression, new Budget(), 0);
    }

    private static Object evaluate(Ast.Expr expression, Budget budget, int depth) {
        if (depth > MAX_DEPTH || ++budget.nodes > MAX_NODES) {
            throw reject("expression exceeds compile-time evaluation budget");
        }

        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Long || value instanceof Boolean || value instanceof OresSymbol) {
                return value;
            }
            if (value instanceof String s) {
                if (s.length() > MAX_STRING_LENGTH) throw reject("string constant is too large");
                return s;
            }
            if (value instanceof Double d && Double.isFinite(d)) {
                return d;
            }
            throw reject("unsupported literal value");
        }

        if (expression instanceof Ast.UnaryExpr unary) {
            Object operand = evaluate(unary.operand(), budget, depth + 1);
            return switch (unary.operator()) {
                case "+" -> {
                    if (operand instanceof Double d) yield finiteFloat(d);
                    yield requireInt(operand);
                }
                case "-" -> {
                    if (operand instanceof Double d) yield finiteFloat(-d);
                    yield checked(() -> Math.negateExact(requireInt(operand)));
                }
                case "~" -> ~requireInt(operand);
                case "!" -> !requireBool(operand);
                default -> throw reject("unsupported unary operator " + unary.operator());
            };
        }

        if (expression instanceof Ast.BinaryExpr binary) {
            // Both operands must independently qualify; this also prevents
            // impure code being hidden behind boolean short-circuiting.
            Object left = evaluate(binary.left(), budget, depth + 1);
            Object right = evaluate(binary.right(), budget, depth + 1);
            return switch (binary.operator()) {
                case "+" -> {
                    if (left instanceof String a && right instanceof String b) {
                        if ((long) a.length() + b.length() > MAX_STRING_LENGTH) {
                            throw reject("string constant is too large");
                        }
                        yield a + b;
                    }
                    yield checked(() -> Math.addExact(requireInt(left), requireInt(right)));
                }
                case "-" -> checked(() -> Math.subtractExact(requireInt(left), requireInt(right)));
                case "*" -> checked(() -> Math.multiplyExact(requireInt(left), requireInt(right)));
                case "%" -> {
                    long divisor = requireInt(right);
                    if (divisor == 0) throw reject("remainder by zero");
                    yield requireInt(left) % divisor;
                }
                case "&&" -> requireBool(left) && requireBool(right);
                case "||" -> requireBool(left) || requireBool(right);
                case "^^" -> requireBool(left) ^ requireBool(right);
                case "&" -> requireInt(left) & requireInt(right);
                case "|" -> requireInt(left) | requireInt(right);
                case "^" -> requireInt(left) ^ requireInt(right);
                case "<<" -> requireInt(left) << shiftDistance(right);
                case ">>" -> requireInt(left) >> shiftDistance(right);
                case ">>>" -> requireInt(left) >>> shiftDistance(right);
                case "eq", "==" -> identicalScalar(left, right);
                case "neq", "!=" -> !identicalScalar(left, right);
                case "<" -> requireInt(left) < requireInt(right);
                case "<=" -> requireInt(left) <= requireInt(right);
                case ">" -> requireInt(left) > requireInt(right);
                case ">=" -> requireInt(left) >= requireInt(right);
                // '/' produces floating-point results even for int operands.
                // Do not accidentally implement integer division here.
                default -> throw reject("unsupported binary operator " + binary.operator());
            };
        }

        if (expression instanceof Ast.ConditionalExpr conditional) {
            boolean condition = requireBool(evaluate(conditional.condition(), budget, depth + 1));
            // Check BOTH branches, including the non-selected branch: an
            // unreachable call or mutation does not become a valid constant.
            Object ifTrue = evaluate(conditional.whenTrue(), budget, depth + 1);
            Object ifFalse = evaluate(conditional.whenFalse(), budget, depth + 1);
            return condition ? ifTrue : ifFalse;
        }

        // Includes calls, names, members, casts, assignments, constructors,
        // containers, borrows, awaits, traps and runtime intrinsics.
        throw reject("runtime/unsupported expression " + expression.getClass().getSimpleName());
    }

    private static double finiteFloat(double value) {
        if (!Double.isFinite(value)) throw reject("non-finite floating-point constant");
        return value;
    }

    private static long requireInt(Object value) {
        if (value instanceof Long n) return n;
        throw reject("operator requires two compile-time integers");
    }

    private static int shiftDistance(Object value) {
        long distance = requireInt(value);
        // Matches the Oreslang runtime's guarded native shift domain.
        // Java's raw shift masks distance modulo 64, which is NOT admitted.
        if (distance < 0 || distance > 63) {
            throw reject("shift distance must be between 0 and 63");
        }
        return (int) distance;
    }

    private static boolean requireBool(Object value) {
        if (value instanceof Boolean b) return b;
        throw reject("operator requires compile-time booleans");
    }

    private static boolean identicalScalar(Object a, Object b) {
        // Match Oreslang's runtime valueEquals for closed scalar constants.
        // Two integers compare exactly, even above 2^53; a float comparison
        // widens to f64. Double.equals would incorrectly distinguish -0.0 and
        // +0.0 and would not recognize 1L == 1.0d.
        if (a instanceof Number left && b instanceof Number right) {
            if (a instanceof Long l && b instanceof Long r) {
                return l.longValue() == r.longValue();
            }
            return left.doubleValue() == right.doubleValue();
        }
        // Symbols are nominal: #ready is not the String "ready", even when
        // the textual names coincide. Other cross-kind scalar pairs are false
        // rather than running any user-defined equality method.
        if (a instanceof OresSymbol || b instanceof OresSymbol) {
            return a instanceof OresSymbol && b instanceof OresSymbol && a.equals(b);
        }
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        throw reject("unsupported compile-time equality operands");
    }

    private static long checked(java.util.function.LongSupplier operation) {
        try {
            return operation.getAsLong();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("final const compile-time integer overflow", overflow);
        }
    }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("final const requires a compile-time constant: " + reason);
    }
}
