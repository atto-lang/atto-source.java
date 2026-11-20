package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Pure host-side admission of typed strided views over an already validated
 * owned buffer. Does not grant a memory pointer, unique owner, borrow lease,
 * or permission to mutate guest actor state. The compiler must bind the byte
 * extent to the actual owner's lifetime before using a successful result.
 */
public final class NdArrayViewAdmission {
    private NdArrayViewAdmission() { }
    public record Range(long begin, long end) { }

    public static Range check(long[] shape, long[] stridesBytes, long offset,
                              long storageBytes, long itemSize, boolean writable) {
        if (shape == null || stridesBytes == null || shape.length != stridesBytes.length
                || shape.length > 16 || itemSize < 1 || itemSize > 64
                || offset < 0 || storageBytes < 0 || offset % itemSize != 0) {
            throw new IllegalArgumentException("NdArray rank, size or alignment invalid");
        }
        shape = shape.clone();
        stridesBytes = stridesBytes.clone();
        boolean empty = false;
        var axes = new ArrayList<long[]>();
        for (int i = 0; i < shape.length; i++) {
            if (shape[i] < 0 || stridesBytes[i] == Long.MIN_VALUE) {
                throw new IllegalArgumentException("NdArray negative shape or stride overflow");
            }
            long stride = Math.abs(stridesBytes[i]);
            if (stride % itemSize != 0) {
                throw new IllegalArgumentException("unaligned NdArray stride");
            }
            if (shape[i] == 0) empty = true;
            if (shape[i] > 1) axes.add(new long[]{stride, shape[i]});
        }
        if (empty) {
            if (offset > storageBytes) throw new IndexOutOfBoundsException("empty view beyond owner");
            return new Range(offset, offset);
        }
        long low = offset, high = offset;
        for (int i = 0; i < shape.length; i++) {
            long extent;
            try { extent = Math.multiplyExact(shape[i] - 1, Math.abs(stridesBytes[i])); }
            catch (ArithmeticException exc) { throw new IllegalArgumentException("NdArray stride extent overflow", exc); }
            try {
                if (stridesBytes[i] < 0) low = Math.subtractExact(low, extent);
                else high = Math.addExact(high, extent);
            } catch (ArithmeticException exc) {
                throw new IllegalArgumentException("NdArray view offset overflow", exc);
            }
        }
        if (low < 0 || high > storageBytes - itemSize) {
            throw new IndexOutOfBoundsException("NdArray view escapes owner");
        }
        if (writable) {
            axes.sort(Comparator.comparingLong(axis -> axis[0]));
            long span = itemSize;
            for (long[] axis : axes) {
                if (axis[0] < span) {
                    throw new IllegalArgumentException("aliased mutable NdArray view");
                }
                try {
                    span = Math.addExact(span, Math.multiplyExact(axis[1] - 1, axis[0]));
                } catch (ArithmeticException exc) {
                    throw new IllegalArgumentException("mutable NdArray span overflow", exc);
                }
            }
        }
        return new Range(low, high + itemSize);
    }
}
