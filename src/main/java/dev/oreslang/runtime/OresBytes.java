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

    /**
     * Checked source-level Bytes construction from Ores integer values.
     * The created backing array is owned exclusively by the returned value.
     */
    public static OresBytes fromUnsignedIntegers(java.util.List<?> values) {
        Objects.requireNonNull(values, "values");
        byte[] owned = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            final long octet;
            if (value instanceof Byte byteValue) {
                octet = Byte.toUnsignedInt(byteValue);
            } else if (value instanceof Short shortValue) {
                octet = shortValue.longValue();
            } else if (value instanceof Integer intValue) {
                octet = intValue.longValue();
            } else if (value instanceof Long longValue) {
                octet = longValue;
            } else if (value instanceof java.math.BigInteger bigValue
                    && bigValue.signum() >= 0
                    && bigValue.bitLength() <= 8) {
                octet = bigValue.longValue();
            } else {
                throw new IllegalArgumentException(
                        "Bytes elements must be integers in 0..255");
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException(
                        "Bytes elements must be integers in 0..255");
            }
            owned[i] = (byte) octet;
        }
        return new OresBytes(owned, false);
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

    void writeTo(java.io.OutputStream output) throws java.io.IOException {
        output.write(bytes);
    }

    void putInto(java.nio.ByteBuffer output) {
        output.put(bytes);
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
