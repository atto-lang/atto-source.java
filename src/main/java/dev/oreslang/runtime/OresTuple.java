package dev.oreslang.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * Immutable Oreslang tuple value.
 *
 * Internally it remains List-compatible for indexing/destructuring, while the
 * Truffle interop contract exposes it as a read-only guest array. Equality is
 * tuple-only so a tuple never becomes equal to an array/list merely because
 * the elements happen to match.
 */
@ExportLibrary(InteropLibrary.class)
public final class OresTuple extends AbstractList<Object> implements RandomAccess, TruffleObject {
    private final List<Object> elements;

    public OresTuple(List<Object> elements) {
        this.elements = List.copyOf(elements);
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
