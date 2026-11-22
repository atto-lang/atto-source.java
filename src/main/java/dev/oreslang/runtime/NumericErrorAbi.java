package dev.oreslang.runtime;

/**
 * Stable guest numeric error identities from contracts/v1/numeric-error-abi.json.
 *
 * <p>These codes are language ABI identity, not Java exception identity or
 * process exit status. Keep this class allocation-free and dependency-free so
 * native/AOT paths can share the constants without introducing host semantics.
 */
public final class NumericErrorAbi {
    private NumericErrorAbi() {}

    public static final int ARITHMETIC_OVERFLOW = 0x0001_0001;
    public static final int DIVISION_BY_ZERO = 0x0001_0002;
}
