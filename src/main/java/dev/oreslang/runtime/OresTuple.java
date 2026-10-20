package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Immutable, nominal runtime carrier of an Oreslang positional Tuple.
 *
 * <p>The List interface is only a read-only host adapter for existing
 * indexed/iterable lowering. The dedicated Java class is the runtime-kind
 * witness: a general Array/List must not match a Tuple, even at equal arity.
 * A checked callable/pattern supplies the per-position type proof; the
 * carrier retains the value positions and their exact arity.</p>
 *
 * <p>Host equality/hash and logging deliberately do NOT traverse elements.
 * Oreslang value equality and display use the evaluator's separate bounded
 * guest semantics. This prevents hostile host callback execution, recursive
 * cycles and accidental Array/Tuple equality in Java collections.</p>
 */
public final class OresTuple extends AbstractList<Object>
        implements RandomAccess, OresMutex.SharedState {
    private final Object[] elements;

    public OresTuple(List<?> values) {
        Objects.requireNonNull(values, "tuple values");
        this.elements = values.toArray();
        for (Object element : elements) {
            Objects.requireNonNull(element, "tuple element");
        }
    }

    @Override
    public Object get(int index) {
        return elements[index];
    }

    @Override
    public int size() {
        return elements.length;
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
        return this;
    }
}
