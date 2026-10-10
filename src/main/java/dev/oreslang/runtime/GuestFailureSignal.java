package dev.oreslang.runtime;

/**
 * Typed language-level failure identity retained across await, actor and
 * supervisor boundaries. The host runtime must never infer guest panic from
 * arbitrary JVM exceptions or class-name strings.
 */
public abstract class GuestFailureSignal extends RuntimeException {
    private final SupervisorFailureLane.Kind kind;

    protected GuestFailureSignal(SupervisorFailureLane.Kind kind, String message) {
        super(message, null, true, false);
        if (kind != SupervisorFailureLane.Kind.THROW
                && kind != SupervisorFailureLane.Kind.RAISE
                && kind != SupervisorFailureLane.Kind.PANIC) {
            throw new IllegalArgumentException("invalid guest failure effect: " + kind);
        }
        this.kind = kind;
    }

    public final SupervisorFailureLane.Kind kind() { return kind; }
}
