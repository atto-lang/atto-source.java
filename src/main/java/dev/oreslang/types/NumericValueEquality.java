package dev.oreslang.types;

/**
 * Exact, symmetric numeric equality for statically admitted guest values.
 *
 * <p>Use the same exact-value relation for equality as for ordering:
 * {@code BigDecimal("0.1")} does not equal binary64 {@code 0.1d}, which
 * represents a different rational number. NaN is never equal, even to itself;
 * signed zeros are equal; and integers beyond binary64 precision are preserved.
 * Unknown host Number carriers are rejected rather than coerced through double.
 *
 * <p>This helper does NOT authorize bigint/decimal guest syntax or mixed-type
 * implicit conversions. Those must be proven independently by the checker.
 */
public final class NumericValueEquality {
    private NumericValueEquality() { }

    public static boolean equals(Number left, Number right) {
        Integer comparison = NumericValueOrdering.compare(left, right);
        return comparison != null && comparison == 0;
    }
}
