package dev.oreslang.runtime;

/**
 * Versioned host-side signal for the experimental checked-i64 source profile.
 *
 * The identity below matches the common HIR/MIR contract and LLVM native
 * CheckedI64Failure. The numeric effect code is an internal Throw status,
 * NEVER a process exit code or an actor-supervisor CONTROL notice.
 *
 * This is not yet a source-constructed guest Error: host/guest catch and
 * actor supervision still require a dedicated effect/unwind integration.
 * No operand data or host ArithmeticException is retained.
 */
public final class CheckedI64OverflowException extends IllegalArgumentException {
    public static final String ERROR_CODE = "ORES_I64_OVERFLOW";
    public static final String GUEST_ERROR_NAME = "ArithmeticOverflowError";
    public static final String EFFECT_KIND = "Throw";
    public static final int EFFECT_CODE = 0x494F0001;

    // Stable ABI tags, shared with LLVM CheckedI64Failure::Operation.
    public static final int OP_ADD = 1;
    public static final int OP_SUBTRACT = 2;
    public static final int OP_MULTIPLY = 3;
    public static final int OP_NEGATE = 4;

    private final String operation;
    private final int operationTag;

    private CheckedI64OverflowException(String operation, int operationTag) {
        super(ERROR_CODE + ": checked signed 64-bit " + operation);
        this.operation = operation;
        this.operationTag = operationTag;
    }

    public static CheckedI64OverflowException forOperator(char operation) {
        return switch (operation) {
            case '+' -> new CheckedI64OverflowException("add", OP_ADD);
            case '-' -> new CheckedI64OverflowException("subtract", OP_SUBTRACT);
            case '*' -> new CheckedI64OverflowException("multiply", OP_MULTIPLY);
            default -> throw new IllegalArgumentException("unsupported checked i64 operator");
        };
    }

    public static CheckedI64OverflowException forNegation() {
        return new CheckedI64OverflowException("negate", OP_NEGATE);
    }

    public String errorCode() { return ERROR_CODE; }
    public String guestErrorName() { return GUEST_ERROR_NAME; }
    public String effectKind() { return EFFECT_KIND; }
    public int effectCode() { return EFFECT_CODE; }
    public String operation() { return operation; }
    public int operationTag() { return operationTag; }
}
