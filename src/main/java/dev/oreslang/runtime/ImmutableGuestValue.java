package dev.oreslang.runtime;

import java.util.List;

/**
 * Closed trust boundary for deeply immutable guest DATA values whose internal
 * storage may safely retain identity across trusted actor freeze operations.
 */
public sealed interface ImmutableGuestValue extends OresMutex.SharedState
        permits OresComplex, OresMatrix, OresVector {

    /** Conservative language-level quota footprint, not host object layout. */
    long logicalBytes();

    @Override
    default Iterable<?> sharedStateChildren() {
        return List.of();
    }
}
