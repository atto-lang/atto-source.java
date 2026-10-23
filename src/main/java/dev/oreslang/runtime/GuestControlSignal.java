package dev.oreslang.runtime;

/**
 * Guest-language exceptional control channels.
 *
 * <p>Throw is an ordinary guest error and may be handled by try/catch or
 * converted to TrapResult at a trap boundary. Raise may be handled only
 * by an eligible recovery boundary. Panic is never locally consumable and
 * must escape to the applicable supervisor/termination boundary.
 * Cancellation and forced termination use separate runtime signals.
 *
 * <p>These draft host constructors are not yet compiler-authenticated.
 * They must be sealed behind trusted VM-issued provenance before exposing
 * recover admission to untrusted guest or foreign callers.
 */
public abstract sealed class GuestControlSignal extends RuntimeException
        permits GuestControlSignal.Throw, GuestControlSignal.Raise, GuestControlSignal.Panic {
    private final Object payload;
    private final String kind;

    private GuestControlSignal(String kind, Object payload) {
        super("Oreslang " + kind + " signal", null, true, true);
        this.kind = kind;
        this.payload = payload;
    }

    public final Object payload() {
        return payload;
    }

    public final String kind() {
        return kind;
    }

    public static final class Throw extends GuestControlSignal {
        public Throw(Object payload) {
            super("throw", payload);
        }
    }

    public static final class Raise extends GuestControlSignal {
        public Raise(Object payload) {
            super("raise", payload);
        }
    }

    public static final class Panic extends GuestControlSignal {
        public Panic(Object payload) {
            super("panic", payload);
        }
    }
}
