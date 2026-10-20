package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Immutable nominal runtime representation of the built-in Entry<K,V> value.
 *
 * <p>Entry is deliberately not represented as a Map/record: preserving this
 * tag prevents a structural {key,value} record from masquerading as an Entry
 * and lets actor transport retain the nominal product across copy/freeze.</p>
 */
public record OresEntry(Object key, Object value) implements OresMutex.SharedState {
    public OresEntry {
        Objects.requireNonNull(key, "Entry key");
        Objects.requireNonNull(value, "Entry value");
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return List.of(key, value);
    }
}
