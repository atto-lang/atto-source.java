package dev.oreslang.runtime;

import java.io.Serial;

/**
 * Host-side checked-i64 remainder-by-zero signal, not the guest Error wire ABI.
 * Evidence adapters must normalize observed failures to division_by_zero and
 * must not treat a host exception or process exit code as actor CONTROL.
 */
public final class CheckedI64DivisionByZeroException extends IllegalArgumentException {
    @Serial private static final long serialVersionUID = 1L;
    public static final String ERROR_CODE = "ORES_I64_DIVISION_BY_ZERO";
    public static final String GUEST_ERROR_NAME = "DivisionByZeroError";
    public static final String EFFECT_KIND = "Throw";
    // Provisional: not an allocated ABI-wide effect code.
    private CheckedI64DivisionByZeroException() {
        super(ERROR_CODE + ": checked signed 64-bit remainder by zero");
    }
    public static CheckedI64DivisionByZeroException forRemainder() {
        return new CheckedI64DivisionByZeroException();
    }
    public String errorCode() { return ERROR_CODE; }
    public String guestErrorName() { return GUEST_ERROR_NAME; }
    public String effectKind() { return EFFECT_KIND; }
}
