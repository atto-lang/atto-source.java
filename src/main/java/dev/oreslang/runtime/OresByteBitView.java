package dev.oreslang.runtime;

import java.util.Objects;

/**
 * A bounded, borrowed (not owning) bit range over immutable actor-owned Bytes.
 *
 * <p>Host-side lowering primitive only. This is not a guest-visible ByteView
 * or BitView type until static lifetime/escape checks and match-arm cleanup
 * admission are implemented. A take/close of the original owner invalidates
 * this view; the view does NOT pin backing storage across actor transfers.
 * Neither the constructor nor any method copies the underlying payload.
 *
 * <p>Offsets and lengths are measured in bits to preserve non-byte-aligned
 * suffixes. All reads revalidate the owner, even for zero-length views.
 */
public final class OresByteBitView {
    private final OresBytes owner;
    private final int startBit;
    private final int bitLength;

    private OresByteBitView(OresBytes owner, int startBit, int bitLength) {
        this.owner = Objects.requireNonNull(owner, "owner");
        int maxBits = Math.multiplyExact(owner.size(), 8);
        if (startBit < 0 || bitLength < 0 || startBit > maxBits
                || bitLength > maxBits - startBit) {
            throw new IndexOutOfBoundsException("Bytes view outside owner bit range");
        }
        this.startBit = startBit;
        this.bitLength = bitLength;
    }

    public static OresByteBitView of(OresBytes owner) {
        Objects.requireNonNull(owner, "owner");
        return new OresByteBitView(owner, 0, Math.multiplyExact(owner.size(), 8));
    }

    public int bitLength() {
        owner.size();
        return bitLength;
    }

    public int startBit() {
        owner.size();
        return startBit;
    }

    public int bitAt(int index) {
        if (index < 0 || index >= bitLength)
            throw new IndexOutOfBoundsException("bit view index " + index);
        int position = startBit + index;
        return (owner.byteAt(position >>> 3) >>> (7 - (position & 7))) & 1;
    }

    public boolean byteAligned() {
        owner.size();
        return (startBit & 7) == 0 && (bitLength & 7) == 0;
    }

    public int byteLength() {
        if (!byteAligned()) throw new IllegalStateException("unaligned bit view");
        return bitLength >>> 3;
    }

    public int byteAt(int index) {
        if (!byteAligned()) throw new IllegalStateException("unaligned bit view");
        int size = bitLength >>> 3;
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("byte view index " + index);
        return owner.byteAt((startBit >>> 3) + index);
    }

    /**
     * Bit-precise prefix consumption. The suffix shares the existing owner:
     * no hidden buffer copy, no byte rounding, no new ownership authority.
     */
    public OresByteBitView skipBits(int prefix) {
        owner.size();
        if (prefix < 0 || prefix > bitLength)
            throw new IndexOutOfBoundsException("bit view prefix " + prefix);
        return new OresByteBitView(owner, Math.addExact(startBit, prefix), bitLength - prefix);
    }

    /** Slice within this view only; end is exclusive. */
    public OresByteBitView bitSlice(int begin, int end) {
        owner.size();
        if (begin < 0 || end < begin || end > bitLength)
            throw new IndexOutOfBoundsException("bit view slice");
        return new OresByteBitView(owner, Math.addExact(startBit, begin), end - begin);
    }
}
