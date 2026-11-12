package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, value-semantic binary data for a future compiler-known Atto Bytes.
 *
 * Parsing a detached NIO receive frame owns its private byte array; public
 * construction and extraction always copy. No mutable ByteBuffer, array or
 * host object can escape into an actor mailbox or Map key.
 *
 * This class alone does NOT admit Bytes across guest actor/type boundaries:
 * compiler, mailbox, map and backend ownership rules must be proven together.
 */
public final class OresBytes {
    public static final int MAX_LENGTH = 1024 * 1024;
    private static final OresBytes EMPTY = new OresBytes(new byte[0]);

    public static OresBytes empty() { return EMPTY; }
    private final byte[] data;
    private final int hash;

    private OresBytes(byte[] exclusivelyOwned) {
        if (exclusivelyOwned.length > MAX_LENGTH) {
            throw new IllegalArgumentException("Bytes exceeds 1 MiB");
        }
        data = exclusivelyOwned;
        hash = Arrays.hashCode(data);
    }

    /** Copies untrusted caller storage before capturing it. */
    public static OresBytes copyOf(byte[] input) {
        Objects.requireNonNull(input, "byte input");
        if (input.length > MAX_LENGTH) {
            throw new IllegalArgumentException("Bytes exceeds 1 MiB");
        }
        return new OresBytes(input.clone());
    }

    /**
     * Package-private parser ownership transfer, with no externally visible
     * backing reference. Only call after allocating an exclusive frame array.
     */
    static OresBytes fromParserOwned(byte[] exclusivelyOwned) {
        return new OresBytes(Objects.requireNonNull(exclusivelyOwned, "parser frame"));
    }

    public int size() { return data.length; }

    public int unsignedAt(int index) { return Byte.toUnsignedInt(data[index]); }

    /** Never expose the mutable backing buffer to guest or host consumers. */
    public byte[] copyToArray() { return data.clone(); }

    /** Force a physically separate heap payload when entering a private actor. */
    OresBytes isolateCopy() { return new OresBytes(data.clone()); }

    /** Trusted runtime writer copies into the detached NIO response frame. */
    void copyInto(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        System.arraycopy(data, 0, destination, offset, data.length);
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof OresBytes bytes
                && Arrays.equals(data, bytes.data);
    }

    @Override public int hashCode() { return hash; }

    @Override public String toString() {
        return "Bytes<opaque:" + data.length + ">";
    }
}
