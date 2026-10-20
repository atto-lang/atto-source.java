package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * A nominal immutable tuple carrier. It implements List for indexed and spread
 * operations, but guest type tests, patterns and equality must check its
 * explicit tag instead of inferring tuple-ness from a Java List shape.
 *
 * Java host equals/hashCode are deliberately identity-based; Oreslang value
 * equality is defined by the evaluator, like the nominal Entry carrier.
 */
public final class OresTuple extends AbstractList<Object>
        implements RandomAccess, OresMutex.SharedState {
    private final List<Object> elements;

    public OresTuple(List<?> values) {
        Objects.requireNonNull(values, "tuple elements");
        this.elements = List.copyOf(values);
    }

    @Override
    public Object get(int index) {
        return elements.get(index);
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "OresTuple<opaque>";
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return elements;
    }
}
