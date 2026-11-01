package dev.oreslang.runtime;

/**
 * JDK 21+ source-only host smoke; no JUnit, Maven, Oreslang guest or network
 * is required. The real JDK 25 JUnit and Native Image gates remain separate.
 */
public final class FrozenTcpSlabHostSmoke {
    public static void main(String[] ignored) {
        byte[] source = {0, 1, 127, (byte)128, (byte)255};
        FrozenTcpSlab slab = new FrozenTcpSlab(source);
        source[3] = 99;
        FrozenTcpSlab.Pin first = slab.pin();
        FrozenTcpSlab.Pin second = slab.pin();
        if (slab.readByte(first, 3) != 128)
            throw new AssertionError("mutable publication alias");
        slab.retire();
        if (slab.freed()) throw new AssertionError("early retirement");
        slab.releasePin(first);
        if (slab.freed() || slab.readByte(second, 4) != 255)
            throw new AssertionError("pinned snapshot invalidated");
        slab.releasePin(second);
        if (!slab.freed()) throw new AssertionError("not quiescent");
        try {
            slab.readByte(second, 0);
            throw new AssertionError("released pin was accepted");
        } catch (IllegalStateException expected) {
            // One-use pin was consumed.
        }
        try {
            new FrozenTcpSlab(new byte[1024 * 1024 + 1]);
            throw new AssertionError("oversized slab admitted");
        } catch (IllegalArgumentException expected) {
            // Bounded publication.
        }
        System.out.println("JAVA_FROZEN_TCP_SLAB_HOST_SMOKE_PASS");
    }
}
