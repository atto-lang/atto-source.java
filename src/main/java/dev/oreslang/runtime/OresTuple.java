package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable nominal carrier for Oreslang's fixed positional Tuple.
 *
 * <p>Intentionally NOT a java.util.Collection or java.util.List. Otherwise
 * host collection operations could invoke component-defined equals/hashCode
 * callbacks, and List equality could confuse Arrays and Tuples. Existing
 * sequence lowering adapts explicitly through a readonly {@link #elements()}
 * view when a positional Java List is unavoidable.</p>
 *
 * <p>TypeChecker and checked callable/pattern boundaries supply precise
 * positional type proofs; this runtime kind tag and immutable arity must be
 * retained through every copy, actor, and async boundary.</p>
 */
public final class OresTuple implements Iterable<Object>, OresMutex.SharedState {
    private final Object[] elements;

    public OresTuple(List<?> values) {
        Objects.requireNonNull(values, "tuple values");
        this.elements = values.toArray();
        for (Object element : elements) {
            Objects.requireNonNull(element, "tuple element");
        }
    }

    public Object get(int index) {
        return elements[index];
    }

    public int size() {
        return elements.length;
    }

    /**
     * Read-only sequence adapter: never a runtime Tuple kind witness.
     * This must not escape in place of the actual OresTuple across a boundary.
     */
    public List<Object> elements() {
        return java.util.Collections.unmodifiableList(Arrays.asList(elements));
    }

    @Override public Iterator<Object> iterator() {
        return elements().iterator();
    }

    @Override public boolean equals(Object other) {
        return this == other;
    }

    @Override public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override public String toString() {
        return "OresTuple<opaque>";
    }

    @Override public Iterable<?> sharedStateChildren() {
        return this;
    }
}
