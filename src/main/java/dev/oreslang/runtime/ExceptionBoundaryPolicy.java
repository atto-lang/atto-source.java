package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Pure terminal boundary policy. Ordinary call frames retain typed Throw/Raise;
 * only an actor, task, or root domain boundary promotes unhandled errors to
 * Panic. This does not enable guest exceptional syntax or wire the actor VM.
 *
 * Original diagnostics are copied to trusted, immutable host-owned data
 * before the failing domain can be retired. The numeric origin code matches
 * the 32-bit native EffectStatus ABI; raw Throwable/guest references must not
 * cross a supervision boundary.
 */
public final class ExceptionBoundaryPolicy {
    private ExceptionBoundaryPolicy() { }

    private static final long MAX_ORIGIN_CODE = 0xffff_ffffL;

    public enum Effect {
        RETURN, THROW, RAISE, PANIC, CANCEL, HOST_FATAL
    }

    public record Terminal(
            Effect delivered, Effect origin, long originCode,
            boolean failed, int defaultExitStatus) {
        public Terminal {
            Objects.requireNonNull(delivered, "delivered");
            Objects.requireNonNull(origin, "origin");
            if (originCode < 0 || originCode > MAX_ORIGIN_CODE) {
                throw new IllegalArgumentException("originCode must be an unsigned 32-bit value");
            }
            if (failed != (delivered != Effect.RETURN)
                    || defaultExitStatus != (failed ? 1 : 0)
                    || (origin != delivered && !(delivered == Effect.PANIC
                    && (origin == Effect.THROW || origin == Effect.RAISE)))) {
                throw new IllegalArgumentException("invalid terminal classification");
            }
        }
    }

    public static Terminal actorTerminal(Effect unhandled) {
        return actorTerminal(unhandled, 0);
    }

    public static Terminal actorTerminal(Effect unhandled, long originCode) {
        Objects.requireNonNull(unhandled, "unhandled");
        if (unhandled == Effect.RETURN) {
            throw new IllegalArgumentException("normal actor return is not a failure");
        }
        return terminal(unhandled, originCode);
    }

    public static Terminal taskOrRootTerminal(Effect unhandled) {
        return taskOrRootTerminal(unhandled, 0);
    }

    public static Terminal taskOrRootTerminal(Effect unhandled, long originCode) {
        Objects.requireNonNull(unhandled, "unhandled");
        return terminal(unhandled, originCode);
    }

    private static Terminal terminal(Effect original, long originCode) {
        final Effect delivered = switch (original) {
            case THROW, RAISE, PANIC -> Effect.PANIC;
            case RETURN, CANCEL, HOST_FATAL -> original;
        };
        final boolean failed = delivered != Effect.RETURN;
        return new Terminal(delivered, original, originCode, failed, failed ? 1 : 0);
    }
}
