package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Immutable *nominal* carrier for an Oreslang positional Tuple.
 *
 * <p>Array/List values may have exactly the same elements and still are not
 * tuples. Implementing List supplies existing read-only iteration and indexed
 * access without erasing the runtime kind needed by tuple patterns and typed
 * boundaries. This type must never be inferred from a host List implementation
 * or mutable-versus-immutable collection heuristics.</p>
 *
 * <p>Host equals/hashCode use identity. Guest == is checked separately by the
 * Oreslang evaluator and recurses through tuple slots with guest value rules.
 * Host logging is opaque to avoid invoking attacker-provided slot toString(),
 * equality, hashCode, or cyclic graph traversal.</p>
 */
public final class OresTuple extends AbstractList<Object>
        implements RandomAccess, OresMutex.SharedState {

    private final List<Object> elements;

    public OresTuple(List<?> items) {
        Objects.requireNonNull(items, "tuple elements");
        // Copy on admission so a mutable host array/list cannot mutate a
        // tuple after the type tag has been established.
        this.elements = List.copyOf(new ArrayList<Object>(items));
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
