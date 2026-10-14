package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Non-catchable, non-trappable actor termination control effect.
 *
 * No guest object, heap reference, source-supplied message, stack trace or
 * host cause is retained. Only the statically checked effect kind crosses
 * from an actor to its supervisor's immutable CONTROL notification.
 */
public final class GuestActorEffectSignal extends RuntimeException {
    public enum Kind { RAISE, PANIC }

    // Immutable singletons: throwing a typed effect does not allocate a new
    // host Throwable per turn. There is no writable stack, cause, suppressed
    // list, guest payload or actor-specific state to race across carriers.
    private static final GuestActorEffectSignal RAISE_SIGNAL =
            new GuestActorEffectSignal(Kind.RAISE);
    private static final GuestActorEffectSignal PANIC_SIGNAL =
            new GuestActorEffectSignal(Kind.PANIC);

    private final Kind kind;

    private GuestActorEffectSignal(Kind kind) {
        super("typed guest actor " + kind.name().toLowerCase(),
                null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public static GuestActorEffectSignal raise() {
        return RAISE_SIGNAL;
    }

    public static GuestActorEffectSignal panic() {
        return PANIC_SIGNAL;
    }

    public Kind kind() { return kind; }
}
