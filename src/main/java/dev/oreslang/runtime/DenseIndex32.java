package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Boundary-level typed identity for one row in a {@link DenseIndexTable}.
 *
 * <p>The physical column representation remains one unsigned 32-bit word
 * (zero is reserved for null; nonzero words encode index + 1). This wrapper
 * carries the table identity, table epoch and slot generation OUTSIDE that
 * word. It is intended for validation at in-process API and ownership boundaries, not for allocation in
 * a hot SoA/AoSoA loop. It is deliberately not Serializable or a wire format;
 * persistence and cross-process migration require a separately versioned
 * stable table identity/epoch/generation encoding.</p>
 *
 * <p>A raw word copied from {@code ActorDenseIndexColumns} is transient and
 * has no generation or table authority by itself.</p>
 */
public final class DenseIndex32<TTag> {
    private final Object tableIdentity;
    private final long tableEpoch;
    private final int encodedWord;
    private final long generation;

    DenseIndex32(Object tableIdentity, long tableEpoch, int encodedWord, long generation) {
        this.tableIdentity = Objects.requireNonNull(tableIdentity, "tableIdentity");
        if (tableEpoch <= 0 || encodedWord == 0 || generation <= 0) {
            throw new IllegalArgumentException("invalid dense32 identity components");
        }
        this.tableEpoch = tableEpoch;
        this.encodedWord = encodedWord;
        this.generation = generation;
    }

    /** Physical width of the compact storage word, excluding this boundary wrapper. */
    public static int storageWordBytes() {
        return Integer.BYTES;
    }

    /** Unsigned compact storage bits; do not use these bits as a standalone identity. */
    public int encodedWord() {
        return encodedWord;
    }

    /** Decoded unsigned logical row index. */
    public long index() {
        return Integer.toUnsignedLong(encodedWord) - 1L;
    }

    long tableEpoch() {
        return tableEpoch;
    }

    long generation() {
        return generation;
    }

    boolean belongsTo(Object identity) {
        return tableIdentity == identity;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DenseIndex32<?> that
                && tableIdentity == that.tableIdentity
                && tableEpoch == that.tableEpoch
                && encodedWord == that.encodedWord
                && generation == that.generation;
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(tableIdentity);
        result = 31 * result + Long.hashCode(tableEpoch);
        result = 31 * result + encodedWord;
        return 31 * result + Long.hashCode(generation);
    }

    @Override
    public String toString() {
        return "DenseIndex32[index=" + index() + ",epoch=" + tableEpoch
                + ",generation=" + generation + "]";
    }
}
