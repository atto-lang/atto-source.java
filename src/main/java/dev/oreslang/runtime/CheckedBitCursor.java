package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Bounded, reflection-free bit-field decoder suitable as an AOT lowering primitive.
 *
 * <p>The cursor owns a defensive copy of its bytes; it does not expose a mutable
 * view, borrow actor memory, or accept callbacks. A failed read is atomic: both
 * its position and owned input remain unchanged. BIG reads support bit-level
 * offsets; LITTLE reads are deliberately limited to byte-aligned fields.</p>
 *
 * <p>This host primitive is not evidence that any guest compiler emits AOT
 * instructions. A width-64 unsigned result is a raw long bit pattern; callers
 * needing decimal unsigned formatting must use unsigned conversion.</p>
 */
public final class CheckedBitCursor {
    public enum Endian { BIG, LITTLE }

    private final byte[] bytes;
    private long bitOffset;

    public CheckedBitCursor(byte[] input) {
        bytes = Objects.requireNonNull(input, "input").clone();
    }

    public long positionBits() { return bitOffset; }
    public long remainingBits() { return (long) bytes.length * 8L - bitOffset; }
    public int lengthBytes() { return bytes.length; }

    /** Read a width-bit unsigned value as its low-order long bit pattern. */
    public long readUnsigned(int width, Endian endian) {
        Objects.requireNonNull(endian, "endian");
        if (width < 1 || width > 64) {
            throw new IllegalArgumentException("bit width must be in 1..64");
        }
        if (endian == Endian.LITTLE && ((bitOffset & 7L) != 0L || (width & 7) != 0)) {
            throw new IllegalArgumentException("little-endian fields require byte alignment");
        }
        if (width > remainingBits()) {
            throw new IndexOutOfBoundsException("bit field exceeds remaining input");
        }
        long value = 0L;
        if (endian == Endian.LITTLE) {
            int start = (int) (bitOffset >>> 3);
            for (int i = 0; i < width / 8; i++) {
                value |= ((long) bytes[start + i] & 0xffL) << (8 * i);
            }
        } else {
            for (int i = 0; i < width; i++) {
                long index = bitOffset + i;
                int bit = (bytes[(int) (index >>> 3)] >>> (7 - (int) (index & 7L))) & 1;
                value = (value << 1) | bit;
            }
        }
        bitOffset += width;
        return value;
    }

    /** Read a two's-complement signed field with exact sign extension. */
    public long readSigned(int width, Endian endian) {
        long bits = readUnsigned(width, endian);
        return width == 64 ? bits : (bits << (64 - width)) >> (64 - width);
    }

    /** Skip without reading. Invalid offsets never advance the cursor. */
    public void skipBits(long count) {
        if (count < 0L || count > remainingBits()) {
            throw new IndexOutOfBoundsException("skip exceeds remaining input");
        }
        bitOffset += count;
    }
}
