package dev.oreslang.runtime;

/**
 * Signed-64-bit reference oracle for the P0 named-integer migration.
 *
 * <p>These are Java backend implementation helpers, NOT Java interoperability
 * obligations for Oreslang callers. Guest Integer.* binding, native AOT and
 * LLVM/.NET/WASM parity remain separate implementation gates.</p>
 */
public final class StrictIntegerArithmetic {
    private StrictIntegerArithmetic() { }

    /** Truncating remainder: sign follows the dividend; zero divisor traps. */
    public static long rem(long dividend, long divisor) {
        if (divisor == 0) throw new ArithmeticException("integer remainder by zero");
        return dividend % divisor;
    }

    /** Euclidean modulus, always nonnegative and less than |divisor|. */
    public static long mod(long dividend, long divisor) {
        long remainder = rem(dividend, divisor);
        if (remainder >= 0) return remainder;
        // Do not call abs: abs(Long.MIN_VALUE) is not representable.
        return divisor > 0 ? remainder + divisor : remainder - divisor;
    }

    /** Integer exponentiation with nonnegative exponent and checked overflow. */
    public static long pow(long base, long exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("integer exponent must be nonnegative");
        }
        long result = 1;
        while (exponent != 0) {
            if ((exponent & 1L) != 0) result = Math.multiplyExact(result, base);
            exponent >>>= 1;
            if (exponent != 0) base = Math.multiplyExact(base, base);
        }
        return result;
    }

    /** Bitwise XOR of exact two's-complement signed 64-bit bit patterns. */
    public static long bitXor(long left, long right) {
        return left ^ right;
    }
}
