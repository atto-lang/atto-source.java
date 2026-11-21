package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Oreslang's immutable, nominal Entry<K,V> value carrier.
 *
 * <p>The host representation intentionally uses identity equality/hash rather
 * than recursive component equality. Guest equality is implemented by the
 * Oreslang evaluator and recursively compares key/value according to Oreslang
 * value semantics. Host equality and hashing must never substitute for the
 * guest contract, particularly for cyclic mutable host graphs, mixed numeric
 * domains, Symbols, and future typed-Map keys.</p>
 *
 * <p>Standalone guest null is not an Entry component value. Optional absence
 * is represented by Option<T>; rejecting Java null here keeps host-created
 * Entry carriers aligned with the source type contract.</p>
 */
public final class OresEntry implements OresMutex.SharedState {
    private final Object key;
    private final Object value;

    public OresEntry(Object key, Object value) {
        this.key = Objects.requireNonNull(key, "Entry key");
        this.value = Objects.requireNonNull(value, "Entry value");
    }

    public Object key() {
        return key;
    }

    public Object value() {
        return value;
    }

    /**
     * Java host identity is not Oreslang value equality.
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    /**
     * Keep host hash semantics aligned with host identity equality.
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * Host diagnostics must not recursively stringify or disclose payloads.
     * Guest formatting uses the evaluator's bounded, cycle-aware renderer.
     */
    @Override
    public String toString() {
        return "OresEntry<opaque>";
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return List.of(key, value);
    }
}
