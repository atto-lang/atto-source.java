package dev.oreslang.types;

/** Exact numeric equality shared by static pattern proofs and guest values. */
public final class NumericValueEquality {
    private NumericValueEquality() { }
    public static boolean equals(Number left, Number right) {
        return NumericValueOrdering.compare(left, right) == 0;
    }
}
