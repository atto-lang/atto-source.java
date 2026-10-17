package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;

/** Immutable dense column vector backed by the same matrix reference core. */
public final class OresVector implements ImmutableGuestValue {
    private final OresMatrix matrix;

    private OresVector(OresMatrix matrix) {
        if (matrix == null || matrix.cols() != 1) {
            throw new IllegalArgumentException("vector backing matrix must have one column");
        }
        this.matrix = matrix;
    }

    public static OresVector fromValues(List<?> values) {
        return new OresVector(OresMatrix.fromColumnValues(values));
    }

    static OresVector fromMatrix(OresMatrix matrix) {
        return new OresVector(matrix);
    }

    OresMatrix asMatrix() {
        return matrix;
    }

    public int length() {
        return matrix.rows();
    }

    public boolean isComplex() {
        return matrix.isComplex();
    }

    public Object get(int index) {
        return matrix.get(index, 0);
    }

    public OresVector add(OresVector other) {
        requireLength(other);
        return fromMatrix(matrix.add(other.matrix));
    }

    public OresVector subtract(OresVector other) {
        requireLength(other);
        return fromMatrix(matrix.subtract(other.matrix));
    }

    public OresVector scale(Object scalar) {
        return fromMatrix(matrix.scale(scalar));
    }

    public OresVector divide(Object scalar) {
        return fromMatrix(matrix.divide(scalar));
    }

    public OresMatrix outerHermitian(OresVector other) {
        if (other == null) throw new IllegalArgumentException("outer-product vector is required");
        List<List<?>> rows = new ArrayList<>(length());
        for (int i = 0; i < length(); i++) {
            List<Object> row = new ArrayList<>(other.length());
            OresComplex left = OresComplex.of(get(i));
            for (int j = 0; j < other.length(); j++) {
                row.add(left.mul(OresComplex.of(other.get(j)).conjugate()));
            }
            rows.add(row);
        }
        return OresMatrix.fromRows(rows);
    }

    @Override
    public long logicalBytes() {
        long elements = length();
        return Math.addExact(48L, Math.multiplyExact(elements, isComplex() ? 16L : 8L));
    }

    private void requireLength(OresVector other) {
        if (other == null || length() != other.length()) {
            throw new IllegalArgumentException("vector lengths must match");
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("vector[");
        for (int i = 0; i < length(); i++) {
            if (i > 0) out.append(", ");
            out.append(get(i));
        }
        return out.append(']').toString();
    }
}
