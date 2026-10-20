package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Stable actor-failure taxonomy used at language/runtime supervision boundaries.
 *
 * <p>These signals carry only immutable diagnostic text. They must never retain
 * guest heap objects or actor-owned mutable values across an actor boundary.
 * Rich source payload transport belongs to the typed supervisor CONTROL
 * protocol and must use the normal Oreslang ownership/copying rules.
 */
public final class ActorFailure {
    public enum Kind {
        NONE,
        THROW,
        RAISE,
        PANIC,
        RUNTIME_FAILURE
    }

    /** Marker for explicit language/runtime failure control, not arbitrary host faults. */
    public sealed interface Signal permits Thrown, Raised, Panic {
        Kind kind();
    }

    /**
     * Explicit recoverable source throw that escaped every ordinary handler.
     * Source lowering is intentionally not enabled merely by defining this
     * runtime identity.
     */
    public static final class Thrown extends RuntimeException implements Signal {
        private static final long serialVersionUID = 1L;

        public Thrown(String message) {
            super(message, null, true, false);
        }

        @Override public Kind kind() { return Kind.THROW; }
    }

    /**
     * Explicit source raise. Ordinary guest catch/trap must not consume this;
     * only the language's recovery boundary may do so once lowering exists.
     */
    public static final class Raised extends RuntimeException implements Signal {
        private static final long serialVersionUID = 1L;

        public Raised(String message) {
            super(message, null, true, false);
        }

        @Override public Kind kind() { return Kind.RAISE; }
    }

    /** Actor-fatal source panic. */
    public static final class Panic extends RuntimeException implements Signal {
        private static final long serialVersionUID = 1L;

        public Panic(String message) {
            super(message, null, true, false);
        }

        @Override public Kind kind() { return Kind.PANIC; }
    }

    private ActorFailure() { }

    /** Classify a live failure without invoking Throwable formatting. */
    public static Kind kindOf(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return failure instanceof Signal signal
                ? signal.kind()
                : Kind.RUNTIME_FAILURE;
    }

    /**
     * Classify retained terminal metadata by exact runtime signal identity.
     * Unknown class names intentionally fail closed to RUNTIME_FAILURE.
     */
    public static Kind kindFromTypeName(String failureType) {
        if (failureType == null || failureType.isEmpty()) return Kind.NONE;
        if (failureType.equals(Thrown.class.getName())) return Kind.THROW;
        if (failureType.equals(Raised.class.getName())) return Kind.RAISE;
        if (failureType.equals(Panic.class.getName())) return Kind.PANIC;
        return Kind.RUNTIME_FAILURE;
    }
}
