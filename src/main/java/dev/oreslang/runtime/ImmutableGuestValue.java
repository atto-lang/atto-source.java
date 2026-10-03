package dev.oreslang.runtime;

/**
 * Marker for deeply immutable runtime-owned guest values that may cross trusted
 * actor boundaries by reference without exposing mutable host state.
 *
 * Implementations must never expose a mutable backing object. logicalBytes()
 * is a conservative language-level quota footprint, not a JVM layout promise.
 */
public interface ImmutableGuestValue extends OresMutex.SharedState {
    long logicalBytes();
}
