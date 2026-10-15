package dev.oreslang.runtime;

/**
 * Versioned host-side signal for the experimental checked-i64 source profile.
 *
 * The public code and guest error name are fixed across backends. This is a
 * runtime-to-guest-boundary signal, not yet a source-defined Error instance:
 * LLVM and actor supervision still require an explicit effect/status ABI.
 * Neither source operand values nor the Java ArithmeticException escape.
 */
public final class CheckedI64OverflowException extends IllegalArgumentException {
    public static final String ERROR_CODE = "ORES_I64_OVERFLOW";
    public static final String GUEST_ERROR_NAME = "ArithmeticOverflowError";
    private final String operation;

    private CheckedI64OverflowException(String operation) {
        super(ERROR_CODE + ": checked signed 64-bit " + operation);
        this.operation = operation;
    }

    public static CheckedI64OverflowException forOperator(char operation) {
        return switch (operation) {
            case '+' -> new CheckedI64OverflowException("add");
            case '-' -> new CheckedI64OverflowException("subtract");
            case '*' -> new CheckedI64OverflowException("multiply");
            default -> throw new IllegalArgumentException("unsupported checked i64 operator");
        };
    }

    public static CheckedI64OverflowException forNegation() {
        return new CheckedI64OverflowException("negate");
    }

    public String errorCode() { return ERROR_CODE; }
    public String guestErrorName() { return GUEST_ERROR_NAME; }
    public String operation() { return operation; }
}
