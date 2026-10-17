package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable dense row-major binary64 matrix.
 *
 * <p>Complex storage uses split real/imaginary planes. Finite complex matrix
 * multiplication delegates each dot product to {@link Complex128Math}, which
 * accumulates the entire exact dyadic dot product and rounds each output
 * component once. This is intentionally a portable reference path, not a BLAS
 * implementation.</p>
 */
public final class OresMatrix implements ImmutableGuestValue {
    private final int rows;
    private final int cols;
    private final double[] real;
    private final double[] imaginary;

    private OresMatrix(
            int rows,
            int cols,
            double[] real,
            double[] imaginary,
            boolean copy) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("matrix dimensions must be positive");
        }
        int elements;
        try {
            elements = Math.multiplyExact(rows, cols);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "matrix dimensions overflow addressable storage", overflow);
        }
        if (real == null || real.length != elements
                || (imaginary != null && imaginary.length != elements)) {
            throw new IllegalArgumentException("matrix backing storage does not match shape");
        }
        this.rows = rows;
        this.cols = cols;
        this.real = copy ? Arrays.copyOf(real, real.length) : real;
        this.imaginary = imaginary == null
                ? null
                : (copy ? Arrays.copyOf(imaginary, imaginary.length) : imaginary);
    }

    public static OresMatrix fromRows(List<?> sourceRows) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            throw new IllegalArgumentException("matrix literal requires at least one row");
        }
        int rowCount = sourceRows.size();
        int columnCount = -1;
        boolean complex = false;
        for (int r = 0; r < rowCount; r++) {
            Object raw = sourceRows.get(r);
            if (!(raw instanceof List<?> row)) {
                throw new IllegalArgumentException("matrix row " + r + " must be an array/list");
            }
            if (columnCount < 0) {
                columnCount = row.size();
                if (columnCount == 0) {
                    throw new IllegalArgumentException("matrix rows cannot be empty");
                }
            } else if (row.size() != columnCount) {
                throw new IllegalArgumentException(
                        "matrix literal must be rectangular; row " + r + " has "
                                + row.size() + " column(s), expected " + columnCount);
            }
            for (Object item : row) {
                requireScalar(item);
                complex |= item instanceof OresComplex;
            }
        }

        int elements = Math.multiplyExact(rowCount, columnCount);
        double[] real = new double[elements];
        double[] imaginary = complex ? new double[elements] : null;
        for (int r = 0; r < rowCount; r++) {
            List<?> row = (List<?>) sourceRows.get(r);
            for (int c = 0; c < columnCount; c++) {
                int index = r * columnCount + c;
                OresComplex value = OresComplex.of(row.get(c));
                real[index] = value.real();
                if (imaginary != null) imaginary[index] = value.imaginary();
            }
        }
        return new OresMatrix(rowCount, columnCount, real, imaginary, false);
    }

    static OresMatrix fromColumnValues(List<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("vector literal requires at least one element");
        }
        List<List<?>> rows = new ArrayList<>(values.size());
        for (Object value : values) rows.add(List.of(value));
        return fromRows(rows);
    }

    public static OresMatrix identity(int size) {
        if (size <= 0) throw new IllegalArgumentException("identity size must be positive");
        double[] real = new double[Math.multiplyExact(size, size)];
        for (int i = 0; i < size; i++) real[i * size + i] = 1.0;
        return new OresMatrix(size, size, real, null, false);
    }

    public int rows() { return rows; }
    public int cols() { return cols; }
    public boolean isComplex() { return imaginary != null; }

    public Object get(int row, int column) {
        int index = index(row, column);
        return isComplex()
                ? new OresComplex(real[index], imaginary[index])
                : real[index];
    }

    public OresMatrix add(OresMatrix other) {
        requireSameShape(other, "addition");
        boolean complex = isComplex() || other.isComplex();
        double[] outReal = new double[real.length];
        double[] outImaginary = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            outReal[i] = real[i] + other.real[i];
            if (complex) outImaginary[i] = imag(i) + other.imag(i);
        }
        return new OresMatrix(rows, cols, outReal, outImaginary, false);
    }

    public OresMatrix subtract(OresMatrix other) {
        requireSameShape(other, "subtraction");
        boolean complex = isComplex() || other.isComplex();
        double[] outReal = new double[real.length];
        double[] outImaginary = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            outReal[i] = real[i] - other.real[i];
            if (complex) outImaginary[i] = imag(i) - other.imag(i);
        }
        return new OresMatrix(rows, cols, outReal, outImaginary, false);
    }

    public OresMatrix matmul(OresMatrix other) {
        if (other == null || cols != other.rows) {
            throw new IllegalArgumentException(
                    "matrix multiplication shape mismatch: " + rows + "x" + cols
                            + " @ " + (other == null ? "null" : other.rows + "x" + other.cols));
        }
        boolean complex = isComplex() || other.isComplex();
        int elements = Math.multiplyExact(rows, other.cols);
        double[] outReal = new double[elements];
        double[] outImaginary = complex ? new double[elements] : null;
        for (int r = 0; r < rows; r++) {
            int leftOffset = r * cols;
            int outOffset = r * other.cols;
            for (int c = 0; c < other.cols; c++) {
                Complex128Math.Value value = Complex128Math.dotProduct(
                        real, imaginary, leftOffset, 1,
                        other.real, other.imaginary, c, other.cols, cols);
                outReal[outOffset + c] = value.real();
                if (outImaginary != null) outImaginary[outOffset + c] = value.imaginary();
            }
        }
        return new OresMatrix(rows, other.cols, outReal, outImaginary, false);
    }

    public OresVector matmul(OresVector other) {
        if (other == null) throw new IllegalArgumentException("matrix vector is required");
        return OresVector.fromMatrix(matmul(other.asMatrix()));
    }

    public OresMatrix scale(Object scalar) {
        OresComplex factor = OresComplex.of(scalar);
        boolean complex = isComplex() || factor.imaginary() != 0.0;
        double[] outReal = new double[real.length];
        double[] outImaginary = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            OresComplex value = new OresComplex(real[i], imag(i)).mul(factor);
            outReal[i] = value.real();
            if (outImaginary != null) outImaginary[i] = value.imaginary();
        }
        return new OresMatrix(rows, cols, outReal, outImaginary, false);
    }

    public OresMatrix divide(Object scalar) {
        OresComplex divisor = OresComplex.of(scalar);
        double[] outReal = new double[real.length];
        boolean complex = isComplex() || divisor.imaginary() != 0.0;
        double[] outImaginary = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            OresComplex value = new OresComplex(real[i], imag(i)).div(divisor);
            outReal[i] = value.real();
            if (outImaginary != null) outImaginary[i] = value.imaginary();
        }
        return new OresMatrix(rows, cols, outReal, outImaginary, false);
    }

    public OresMatrix transpose() {
        double[] outReal = new double[real.length];
        double[] outImaginary = isComplex() ? new double[real.length] : null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int from = r * cols + c;
                int to = c * rows + r;
                outReal[to] = real[from];
                if (outImaginary != null) outImaginary[to] = imaginary[from];
            }
        }
        return new OresMatrix(cols, rows, outReal, outImaginary, false);
    }

    public OresMatrix hermitian() {
        double[] outReal = new double[real.length];
        double[] outImaginary = isComplex() ? new double[real.length] : null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int from = r * cols + c;
                int to = c * rows + r;
                outReal[to] = real[from];
                if (outImaginary != null) outImaginary[to] = -imaginary[from];
            }
        }
        return new OresMatrix(cols, rows, outReal, outImaginary, false);
    }

    public Object trace() {
        int count = Math.min(rows, cols);
        double sumReal = 0.0;
        double sumImaginary = 0.0;
        for (int i = 0; i < count; i++) {
            int index = i * cols + i;
            sumReal += real[index];
            sumImaginary += imag(index);
        }
        return isComplex() ? new OresComplex(sumReal, sumImaginary) : sumReal;
    }

    public double norm() {
        double result = 0.0;
        for (int i = 0; i < real.length; i++) {
            result = Math.hypot(result, real[i]);
            if (isComplex()) result = Math.hypot(result, imaginary[i]);
        }
        return result;
    }

    @Override
    public long logicalBytes() {
        long elements = Math.multiplyExact((long) rows, (long) cols);
        return Math.addExact(64L, Math.multiplyExact(elements, isComplex() ? 16L : 8L));
    }

    private double imag(int index) {
        return imaginary == null ? 0.0 : imaginary[index];
    }

    private int index(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= cols) {
            throw new IndexOutOfBoundsException(
                    "matrix index (" + row + "," + column + ") outside "
                            + rows + "x" + cols);
        }
        return row * cols + column;
    }

    private void requireSameShape(OresMatrix other, String operation) {
        if (other == null || rows != other.rows || cols != other.cols) {
            throw new IllegalArgumentException(
                    "matrix " + operation + " requires identical shapes");
        }
    }

    private static void requireScalar(Object value) {
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            throw new IllegalArgumentException(
                    "exact BigInt/Decimal matrix values require a separate exact backend");
        }
        if (!(value instanceof Number) && !(value instanceof OresComplex)) {
            throw new IllegalArgumentException(
                    "matrix values must be binary64-compatible numeric scalars");
        }
        OresComplex.of(value);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("matrix[");
        for (int r = 0; r < rows; r++) {
            if (r > 0) out.append(", ");
            out.append('[');
            for (int c = 0; c < cols; c++) {
                if (c > 0) out.append(", ");
                out.append(get(r, c));
            }
            out.append(']');
        }
        return out.append(']').toString();
    }
}
