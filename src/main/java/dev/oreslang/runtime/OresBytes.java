package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compact immutable byte sequence for actor messages and compiler/runtime
 * lowering of Oreslang Bytes values.
 *
 * <p>The backing array is never exposed. Public construction defensively
 * copies input; trusted runtime code may transfer ownership of an already
 * private array through {@link #wrapTrusted(byte[])}.</p>
 */
public final class OresBytes {
    private final byte[] bytes;

    private OresBytes(byte[] bytes, boolean copy) {
        Objects.requireNonNull(bytes, "bytes");
        this.bytes = copy ? bytes.clone() : bytes;
    }

    public static OresBytes copyOf(byte[] bytes) {
        return new OresBytes(bytes, true);
    }

    static OresBytes wrapTrusted(byte[] ownedBytes) {
        return new OresBytes(ownedBytes, false);
    }

    public int size() {
        return bytes.length;
    }

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    public byte byteAt(int index) {
        Objects.checkIndex(index, bytes.length);
        return bytes[index];
    }

    public int unsignedAt(int index) {
        return Byte.toUnsignedInt(byteAt(index));
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }

    /** One-copy actor-local duplicate for PRIVATE/ISOLATED message transport. */
    OresBytes isolatedCopy() {
        return new OresBytes(bytes, true);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof OresBytes that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "OresBytes[length=" + bytes.length + "]";
    }
}
