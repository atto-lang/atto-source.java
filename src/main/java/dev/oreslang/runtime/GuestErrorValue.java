package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Initial guest-owned nominal base Error payload. It is not a Throwable,
 * host exception or transport envelope. The exact instance is retained across
 * ordinary throw/catch/trap; value-based equality would break Error identity.
 *
 * This bounded foundation has no general source constructor, user-defined subclasses or
 * tenant-crossing API. Parser/typechecker admission remains closed until the
 * complete typed Error contract is implemented and executable guest-tested.
 */
public final class GuestErrorValue {
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final String message;
    private final String nominalType;
    private final Object payload;

    private GuestErrorValue(String nominalType, String message, Object payload) {
        this.nominalType = nominalType;
        this.message = message;
        this.payload = payload;
    }

    /** A trusted future guest-constructor lowering must call this only after typechecking. */
    public static GuestErrorValue baseError(String message) {
        Objects.requireNonNull(message, "message");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("guest Error message exceeds length limit");
        }
        return new GuestErrorValue("Error", message, null);
    }

    public String nominalType() {
        return nominalType;
    }

    /** Internal guest-owned payload; no host/tenant transport permission is implied. */
    public Object payload() { return payload; }

    public static GuestErrorValue optionUnwrap() {
        return new GuestErrorValue("OptionUnwrapError", "called Option::unwrap() on a None value", null);
    }

    public static GuestErrorValue resultUnwrap(Object original) {
        return new GuestErrorValue("ResultUnwrapError", "called Result::unwrap() on an Err value", original);
    }

    public String message() {
        return message;
    }

    /** Never emit an untrusted guest message in implicit JVM exception logs. */
    @Override public String toString() {
        return nominalType;
    }
}
