package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, content-addressed guest Bytes value.
 *
 * <p>Constructors snapshot untrusted mutable input before publishing it.
 * No Java arrays, ByteBuffers or mutable views escape to guests. Copies of
 * this value safely share the same frozen backing; keys never change hash.
 * Future zero-copy ownership is a distinct capability and is NOT implied.
 */
public final class OresBytes {
    public static final int MAX_LENGTH = 1_048_576;
    private static final OresBytes EMPTY = new OresBytes(new byte[0], true);
    private final byte[] bytes;
    private final int hash;

    private OresBytes(byte[] input, boolean alreadyOwned) {
        if (input.length > MAX_LENGTH) {
            throw new IllegalArgumentException("Bytes exceeds 1 MiB allocation budget");
        }
        this.bytes = alreadyOwned ? input : input.clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    public static OresBytes empty() {
        return EMPTY;
    }

    public static OresBytes fromArray(byte[] input) {
        Objects.requireNonNull(input, "Bytes input");
        return input.length == 0 ? EMPTY : new OresBytes(input, false);
    }

    /**
     * Package-private trusted NIO completion ownership transfer. This avoids
     * cloning the completed allocation a second time. Callers must never
     * retain or mutate the array after passing it here.
     */
    static OresBytes fromCompletedNioRead(byte[] exclusiveBacking) {
        Objects.requireNonNull(exclusiveBacking, "completed NIO bytes");
        return exclusiveBacking.length == 0
                ? EMPTY : new OresBytes(exclusiveBacking, true);
    }

    /** Trusted transport obtains a read-only view; no public mutable alias. */
    ByteBufferView nioReadOnlyView() {
        return new ByteBufferView(bytes);
    }

    static final class ByteBufferView {
        private final byte[] exclusiveReadOnlyBytes;

        private ByteBufferView(byte[] bytes) {
            exclusiveReadOnlyBytes = bytes;
        }

        java.nio.ByteBuffer asReadOnlyBuffer() {
            return java.nio.ByteBuffer.wrap(exclusiveReadOnlyBytes).asReadOnlyBuffer();
        }
    }

    /** Exact integer byte vectors, never floating values or narrowing casts. */
    public static OresBytes fromList(List<?> input) {
        Objects.requireNonNull(input, "Bytes input");
        if (input.size() > MAX_LENGTH) {
            throw new IllegalArgumentException("Bytes exceeds 1 MiB allocation budget");
        }
        if (input.isEmpty()) return EMPTY;
        byte[] out = new byte[input.size()];
        for (int i = 0; i < out.length; i++) {
            Object value = input.get(i);
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("Bytes element must be integer at index " + i);
            }
            long number = ((Number) value).longValue();
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException("Bytes element outside 0..255 at index " + i);
            }
            out[i] = (byte) number;
        }
        return new OresBytes(out, true);
    }

    public int size() { return bytes.length; }

    public long get(int index) {
        if (index < 0 || index >= bytes.length) {
            throw new IndexOutOfBoundsException("Bytes index out of bounds");
        }
        return Byte.toUnsignedInt(bytes[index]);
    }

    /** Always returns detached mutable guest list backing, never an alias. */
    public List<Object> toList() {
        ArrayList<Object> copy = new ArrayList<>(bytes.length);
        for (byte b : bytes) copy.add((long) Byte.toUnsignedInt(b));
        return copy;
    }

    public byte[] toArrayCopy() {
        return bytes.clone();
    }

    public int wireKeyBytes() {
        return Math.addExact(bytes.length, 5);
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof OresBytes other
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() { return hash; }

    @Override
    public String toString() { return "Bytes<opaque:" + bytes.length + ">"; }
}
