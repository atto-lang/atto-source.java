package dev.oreslang.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.List;

/**
 * Immutable Oreslang tuple value.
 *
 * It is intentionally not a java.util.List subtype: tuple/list identity must
 * stay symmetric even when values cross the Java/Truffle boundary. The
 * evaluator adapts tuples explicitly for indexing, destructuring, and
 * iteration, while interop exposes a read-only guest array.
 */
@ExportLibrary(InteropLibrary.class)
public final class OresTuple implements TruffleObject {
    private final List<Object> elements;

    public OresTuple(List<Object> elements) {
        this.elements = List.copyOf(elements);
    }

    public Object get(int index) {
        return elements.get(index);
    }

    public int size() {
        return elements.size();
    }

    public List<Object> elements() {
        return elements;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OresTuple tuple && elements.equals(tuple.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return elements.toString();
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return elements.size();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < elements.size();
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
        return elements.get((int) index);
    }
}
