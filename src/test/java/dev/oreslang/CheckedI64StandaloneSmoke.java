package dev.oreslang;

import dev.oreslang.runtime.CheckedI64Arithmetic;
import dev.oreslang.runtime.CheckedI64DivisionByZeroException;
import dev.oreslang.runtime.CheckedI64OverflowException;
import java.math.BigInteger;
import java.util.Random;

/**
 * Dependency-free exact source smoke/differential oracle for javac/java.
 *
 * This exercises the very same helper called by the Graal guest evaluator,
 * NOT actual guest program execution, LLVM, JS or native-image parity.
 * Run through scripts/test-checked-i64-standalone.sh on a trusted checkout.
 */
public final class CheckedI64StandaloneSmoke {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    // Pin coverage so silently removed loops cannot report a false green.
    private static final int EXPECTED_CHECKS = 37_555;
    private static int checks;

    private CheckedI64StandaloneSmoke() {}

    private static void pair(char operator, long left, long right) {
        BigInteger a = BigInteger.valueOf(left);
        BigInteger b = BigInteger.valueOf(right);
        if (operator == '%' && right == 0L) {
            try {
                CheckedI64Arithmetic.binary(operator, left, right);
            } catch (CheckedI64DivisionByZeroException correct) {
                require("ORES_I64_DIVISION_BY_ZERO".equals(correct.errorCode()),
                        "zero divisor error code");
                require("DivisionByZeroError".equals(correct.guestErrorName()),
                        "zero divisor guest error label");
                require("Throw".equals(correct.effectKind()),
                        "zero divisor is a Throw effect");
                require(!correct.getMessage().contains("/ by zero"),
                        "host JVM division diagnostic must not leak");
                checks++;
                return;
            }
            throw new AssertionError("missing zero-divisor trap");
        }
        BigInteger mathematical = switch (operator) {
            case '+' -> a.add(b);
            case '-' -> a.subtract(b);
            case '*' -> a.multiply(b);
            case '%' -> a.remainder(b);
            default -> throw new AssertionError("bad oracle operator");
        };
        if (mathematical.compareTo(MIN) < 0 || mathematical.compareTo(MAX) > 0) {
            try {
                CheckedI64Arithmetic.binary(operator, left, right);
            } catch (CheckedI64OverflowException correct) {
                int tag = operator == '+' ? 1 : operator == '-' ? 2 : 3;
                require(correct.operationTag() == tag, "wrong overflow tag");
                require("ORES_I64_OVERFLOW".equals(correct.errorCode()), "overflow code");
                require("ArithmeticOverflowError".equals(correct.guestErrorName()),
                        "overflow guest error label");
                require("Throw".equals(correct.effectKind()), "overflow effect kind");
                require(correct.effectCode() == 0x494F0001,
                        "overflow internal effect code");
                String name = operator == '+' ? "add" : operator == '-' ? "subtract" : "multiply";
                require(name.equals(correct.operation()),
                        "overflow operation metadata");
                checks++;
                return;
            }
            throw new AssertionError("missing overflow trap " + operator
                    + " " + left + " " + right);
        }
        long result = CheckedI64Arithmetic.binary(operator, left, right);
        require(result == mathematical.longValueExact(), "wrong result "
                + operator + " " + left + " " + right);
        checks++;
    }

    private static void negate(long value) {
        if (value == Long.MIN_VALUE) {
            try {
                CheckedI64Arithmetic.negate(value);
            } catch (CheckedI64OverflowException correct) {
                require(correct.operationTag() == 4, "wrong unary overflow tag");
                require("negate".equals(correct.operation()),
                        "unary overflow operation label");
                require("ArithmeticOverflowError".equals(correct.guestErrorName()),
                        "unary overflow guest error label");
                require("Throw".equals(correct.effectKind())
                        && correct.effectCode() == 0x494F0001,
                        "unary overflow internal effect");
                checks++;
                return;
            }
            throw new AssertionError("missing unary overflow trap");
        }
        long exact = BigInteger.valueOf(value).negate().longValueExact();
        require(CheckedI64Arithmetic.negate(value) == exact, "wrong unary negation");
        checks++;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        char[] operators = {'+', '-', '*', '%'};
        long[] boundary = {
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -3037000500L,
            -3037000499L, -43L, -1L, 0L, 1L, 43L,
            3037000499L, 3037000500L, Long.MAX_VALUE - 1,
            Long.MAX_VALUE
        };
        for (long left : boundary) {
            for (long right : boundary) {
                for (char operator : operators) pair(operator, left, right);
            }
            negate(left);
        }
        Random pairs = new Random(0x5845d25eL);
        for (int i = 0; i < 4096; i++) {
            long left = pairs.nextLong();
            long right = i % 97 == 0 ? 0L : pairs.nextLong();
            for (char operator : operators) pair(operator, left, right);
        }
        // Uniform 64-bit products mostly overflow; a second independent
        // bounded distribution exercises successful multiplies and remainder
        // across positive, negative and zero operands.
        Random bounded = new Random(0x5a4c13dbL);
        for (int i = 0; i < 4096; i++) {
            long left = bounded.nextInt(1_000_001) - 500_000L;
            long right = i % 47 == 0 ? 0L
                    : bounded.nextInt(1_000_001) - 500_000L;
            for (char operator : operators) pair(operator, left, right);
        }
        Random unary = new Random(0x459a113L);
        for (int i = 0; i < 4096; i++) negate(unary.nextLong());
        require(CheckedI64Arithmetic.binary('%', Long.MIN_VALUE, -1L) == 0L,
                "minimum remainder -1");
        checks++;
        try {
            CheckedI64Arithmetic.binary('/', 43L, 1L);
            throw new AssertionError("integer division was silently admitted");
        } catch (IllegalArgumentException correct) {
            require(!(correct instanceof CheckedI64OverflowException),
                    "unsupported operator mislabeled as overflow");
            checks++;
        }
        require(checks == EXPECTED_CHECKS,
                "standalone test vector coverage changed: " + checks
                        + " expected " + EXPECTED_CHECKS);
        System.out.println("CHECKED_I64_STANDALONE|checks=" + checks + "|passed=" + checks
                + "|failed=0|scope=pure-jvm");
    }
}
