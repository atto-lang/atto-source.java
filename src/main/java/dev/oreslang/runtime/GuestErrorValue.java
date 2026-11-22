package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Initial guest-owned nominal base Error payload. It is not a Throwable,
 * host exception or transport envelope. The exact instance is retained across
 * ordinary throw/catch/trap; value-based equality would break Error identity.
 *
 * This bounded foundation has no source constructor, nominal subclasses or
 * tenant-crossing API. Parser/typechecker admission remains closed until the
 * complete typed Error contract is implemented and executable guest-tested.
 */
public final class GuestErrorValue {
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final String message;

    private GuestErrorValue(String message) {
        this.message = message;
    }

    /** A trusted future guest-constructor lowering must call this only after typechecking. */
    public static GuestErrorValue baseError(String message) {
        Objects.requireNonNull(message, "message");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("guest Error message exceeds length limit");
        }
        return new GuestErrorValue(message);
    }

    public String nominalType() {
        return "Error";
    }

    public String message() {
        return message;
    }

    /** Never emit an untrusted guest message in implicit JVM exception logs. */
    @Override public String toString() {
        return "Error";
    }
}
