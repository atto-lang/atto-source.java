package dev.oreslang.runtime;

/** A proven task dependency cycle or a forbidden carrier-blocking operation. */
public final class OresDeadlockError extends IllegalStateException {
    public OresDeadlockError(String message) { super(message); }
}
