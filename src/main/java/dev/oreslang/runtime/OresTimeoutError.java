package dev.oreslang.runtime;

/** A runtime deadline expired; it does not imply a proven deadlock. */
public final class OresTimeoutError extends RuntimeException {
    public OresTimeoutError(String message) { super(message); }
}
