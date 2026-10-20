package dev.oreslang.runtime;

import java.util.Arrays;

/**
 * Oreslang's immutable, nominal Entry<K,V> value carrier.
 *
 * <p>The host representation intentionally has identity equality/hash rather
 * than a Java record's generated recursive equals/hashCode/toString. Guest
 * equality is implemented by the Oreslang evaluator and recursively compares
 * key/value according to Oreslang value semantics. Host equality and hashing
 * cannot substitute for the guest contract, particularly for cyclic mutable
 * host graphs, mixed numeric domains, Symbols, and future typed-Map keys.</p>
 *
 * <p>The carrier never grants authority to its payloads: actor transport must
 * independently validate and detach both fields. Java null is preserved as
 * Oreslang's null value; null admission is governed by the guest type contract.</p>
 */
public final class OresEntry implements OresMutex.SharedState {
    private final Object key;
    private final Object value;

    public OresEntry(Object key, Object value) {
        this.key = key;
        this.value = value;
    }

    public Object key() {
        return key;
    }

    public Object value() {
        return value;
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return Arrays.asList(key, value);
    }

    /**
     * Source-level diagnostics use the bounded, cycle-aware guest renderer.
     * Never implicitly stringify hostile host payloads on a host carrier.
     */
    @Override
    public String toString() {
        return "OresEntry";
    }
}
