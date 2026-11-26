package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Initial guest-owned nominal base Error payload. It is not a Throwable,
 * host exception or transport envelope. The exact instance is retained across
 * ordinary throw/catch/trap; value-based equality would break Error identity.
 *
 * This bounded foundation has no source constructor, user-defined subclasses or
 * tenant-crossing API. Parser/typechecker admission remains closed until the
 * complete typed Error contract is implemented and executable guest-tested.
 */
public final class GuestErrorValue {
    private static final int MAX_MESSAGE_LENGTH = 4096;

    public enum Kind {
        BASE("Error", 0, "ordinary guest throw"),
        ARITHMETIC_OVERFLOW("ArithmeticOverflowError", 0x0001_0001, "ORES_I64_OVERFLOW"),
        DIVISION_BY_ZERO("DivisionByZeroError", 0x0001_0002, "ORES_I64_DIVISION_BY_ZERO"),
        // Internal candidate codes; not registered in the wire catalog.
        OPTION_UNWRAP("OptionUnwrapError", 0x0001_0003, "ORES_OPTION_UNWRAP"),
        RESULT_UNWRAP("ResultUnwrapError", 0x0001_0004, "ORES_RESULT_UNWRAP");

        private final String nominalType;
        private final int abiCode;
        private final String diagnostic;
        Kind(String nominalType, int abiCode, String diagnostic) {
            this.nominalType = nominalType;
            this.abiCode = abiCode;
            this.diagnostic = diagnostic;
        }
    }

    private final String message;
    private final Kind kind;
    private final Object payload;

    private GuestErrorValue(Kind kind, String message) {
        this(kind, message, null);
    }

    private GuestErrorValue(Kind kind, String message, Object payload) {
        this.kind = kind;
        this.message = message;
        this.payload = payload;
    }

    /** Internal guest-owned payload, not a transport permission. */
    public Object payload() { return payload; }

    public static GuestErrorValue optionUnwrap() {
        return new GuestErrorValue(Kind.OPTION_UNWRAP, "called Option::unwrap() on a None value");
    }

    public static GuestErrorValue resultUnwrap(Object original) {
        return new GuestErrorValue(Kind.RESULT_UNWRAP, "called Result::unwrap() on an Err value", original);
    }

    /** A trusted future guest-constructor lowering must call this only after typechecking. */
    public static GuestErrorValue baseError(String message) {
        Objects.requireNonNull(message, "message");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("guest Error message exceeds length limit");
        }
        return new GuestErrorValue(Kind.BASE, message);
    }

    /** Compiler/runtime-only factories; no caller-supplied nominal names or ABI codes. */
    public static GuestErrorValue arithmeticOverflow() {
        return new GuestErrorValue(Kind.ARITHMETIC_OVERFLOW, Kind.ARITHMETIC_OVERFLOW.diagnostic);
    }

    public static GuestErrorValue divisionByZero() {
        return new GuestErrorValue(Kind.DIVISION_BY_ZERO, Kind.DIVISION_BY_ZERO.diagnostic);
    }

    public String nominalType() { return kind.nominalType; }
    public int abiCode() { return kind.abiCode; }
    public String diagnostic() { return kind.diagnostic; }

    public String message() {
        return message;
    }

    /** Never emit an untrusted guest message in implicit JVM exception logs. */
    @Override public String toString() {
        return kind.nominalType;
    }
}
