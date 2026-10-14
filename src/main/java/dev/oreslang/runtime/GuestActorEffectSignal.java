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

    private final Kind kind;

    private GuestActorEffectSignal(Kind kind) {
        super("typed guest actor " + kind.name().toLowerCase(),
                null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public static GuestActorEffectSignal raise() {
        return new GuestActorEffectSignal(Kind.RAISE);
    }

    public static GuestActorEffectSignal panic() {
        return new GuestActorEffectSignal(Kind.PANIC);
    }

    public Kind kind() { return kind; }
}
