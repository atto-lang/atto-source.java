package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Pure boundary semantics only: this does not enable source throw/raise/panic
 * lowering or automatically wire the existing actor runtime/CLI launcher.
 *
 * Ordinary call frames propagate typed Throw/Raise unchanged. At the actor
 * terminal or non-actor task/root boundary, an unhandled Throw/Raise becomes
 * terminal Panic with an independently retained origin.
 */
public final class ExceptionBoundaryPolicy {
    private ExceptionBoundaryPolicy() { }

    public enum Effect {
        RETURN, THROW, RAISE, PANIC, CANCEL, HOST_FATAL
    }

    public record Terminal(Effect delivered, Effect origin, boolean failed, int defaultExitStatus) {
        public Terminal {
            Objects.requireNonNull(delivered, "delivered");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public static Terminal actorTerminal(Effect unhandled) {
        Objects.requireNonNull(unhandled, "unhandled");
        return switch (unhandled) {
            case RETURN -> throw new IllegalArgumentException("normal actor return is not a failure");
            case THROW, RAISE, PANIC -> new Terminal(Effect.PANIC, unhandled, true, 1);
            case CANCEL -> new Terminal(Effect.CANCEL, Effect.CANCEL, true, 1);
            case HOST_FATAL -> new Terminal(Effect.HOST_FATAL, Effect.HOST_FATAL, true, 1);
        };
    }

    public static Terminal taskOrRootTerminal(Effect unhandled) {
        Objects.requireNonNull(unhandled, "unhandled");
        return switch (unhandled) {
            case RETURN -> new Terminal(Effect.RETURN, Effect.RETURN, false, 0);
            case THROW, RAISE, PANIC -> new Terminal(Effect.PANIC, unhandled, true, 1);
            case CANCEL -> new Terminal(Effect.CANCEL, Effect.CANCEL, true, 1);
            case HOST_FATAL -> new Terminal(Effect.HOST_FATAL, Effect.HOST_FATAL, true, 1);
        };
    }
}
