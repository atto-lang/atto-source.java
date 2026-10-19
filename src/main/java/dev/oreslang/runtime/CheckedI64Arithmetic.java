package dev.oreslang.runtime;

/**
 * Single pure host implementation of the experimental checked signed-i64
 * arithmetic profile. Kept separate from the Truffle evaluator so that a
 * standard JVM can differential-test all operator boundary semantics.
 *
 * No source-level guest Error, actor CONTROL, or LLVM ABI is implied.
 */
public final class CheckedI64Arithmetic {
    private CheckedI64Arithmetic() {}

    public static long binary(char op, long left, long right) {
        if (op == '%') {
            if (right == 0L) {
                throw CheckedI64DivisionByZeroException.forRemainder();
            }
            // Java remainder truncates the mathematical quotient toward zero.
            // Long.MIN_VALUE % -1 is defined to yield zero, not overflow.
            return left % right;
        }
        try {
            return switch (op) {
                case '+' -> Math.addExact(left, right);
                case '-' -> Math.subtractExact(left, right);
                case '*' -> Math.multiplyExact(left, right);
                default -> throw new IllegalArgumentException(
                        "unsupported checked i64 arithmetic operator");
            };
        } catch (ArithmeticException overflow) {
            throw CheckedI64OverflowException.forOperator(op);
        }
    }

    public static long negate(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException overflow) {
            throw CheckedI64OverflowException.forNegation();
        }
    }
}
