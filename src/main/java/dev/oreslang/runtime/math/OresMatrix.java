package dev.oreslang.runtime.math;

import dev.oreslang.runtime.ImmutableGuestValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable dense row-major matrix value.
 *
 * This is the portable Oreslang reference representation: real values use one
 * contiguous f64 plane; complex values use separate contiguous real/imaginary
 * planes. No Java numerical library defines Oreslang matrix semantics.
 *
 * Native BLAS/GPU/JNI backends may accelerate the same operations, but they
 * must preserve these language-level shape, complex-arithmetic and failure
 * semantics.
 */
public final class OresMatrix implements ImmutableGuestValue {
    private final int rows;
    private final int cols;
    private final double[] real;
    private final double[] imaginary; // null for real matrices

    private OresMatrix(int rows, int cols, double[] real, double[] imaginary, boolean copy) {
        if (rows <= 0 || cols <= 0) throw new IllegalArgumentException("matrix dimensions must be positive");
        int elements;
        try {
            elements = Math.multiplyExact(rows, cols);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("matrix dimensions overflow addressable storage", overflow);
        }
        if (real.length != elements || (imaginary != null && imaginary.length != elements)) {
            throw new IllegalArgumentException("matrix backing storage does not match shape");
        }
        this.rows = rows;
        this.cols = cols;
        this.real = copy ? Arrays.copyOf(real, real.length) : real;
        this.imaginary = imaginary == null ? null : (copy ? Arrays.copyOf(imaginary, imaginary.length) : imaginary);
    }

    public static OresMatrix fromRows(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("matrix literal requires at least one row");
        }

        int rowCount = rows.size();
        int columnCount = -1;
        boolean complex = false;
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            Object raw = rows.get(rowIndex);
            if (!(raw instanceof List<?> row)) {
                throw new IllegalArgumentException("matrix row " + rowIndex + " must be an array/list");
            }
            if (columnCount < 0) {
                columnCount = row.size();
                if (columnCount == 0) throw new IllegalArgumentException("matrix rows cannot be empty");
            }
            if (row.size() != columnCount) {
                throw new IllegalArgumentException(
                        "matrix literal must be rectangular; row " + rowIndex + " has "
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
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            List<?> row = (List<?>) rows.get(rowIndex);
            for (int column = 0; column < columnCount; column++) {
                int index = rowIndex * columnCount + column;
                Object item = row.get(column);
                if (item instanceof OresComplex value) {
                    real[index] = value.real();
                    imaginary[index] = value.imaginary();
                } else {
                    real[index] = ((Number) item).doubleValue();
                }
            }
        }
        return new OresMatrix(rowCount, columnCount, real, imaginary, false);
    }

    static OresMatrix fromColumnValues(List<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("vector literal requires at least one element");
        }
        boolean complex = false;
        for (Object value : values) {
            requireScalar(value);
            complex |= value instanceof OresComplex;
        }
        double[] real = new double[values.size()];
        double[] imaginary = complex ? new double[values.size()] : null;
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof OresComplex c) {
                real[i] = c.real();
                imaginary[i] = c.imaginary();
            } else {
                real[i] = ((Number) value).doubleValue();
            }
        }
        return new OresMatrix(values.size(), 1, real, imaginary, false);
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

    /** Conservative actor/mailbox quota footprint, independent of JVM object layout. */
    public long logicalBytes() {
        long elements = Math.multiplyExact((long) rows, (long) cols);
        return Math.addExact(64L, Math.multiplyExact(elements, isComplex() ? 16L : 8L));
    }

    public Object get(int row, int column) {
        int index = index(row, column);
        if (!isComplex()) return real[index];
        return new OresComplex(real[index], imaginary[index]);
    }

    public OresMatrix add(OresMatrix other) {
        requireSameShape(other, "addition");
        boolean complex = isComplex() || other.isComplex();
        double[] outReal = new double[real.length];
        double[] outImag = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            outReal[i] = real[i] + other.real[i];
            if (complex) outImag[i] = imag(i) + other.imag(i);
        }
        return new OresMatrix(rows, cols, outReal, outImag, false);
    }

    public OresMatrix subtract(OresMatrix other) {
        requireSameShape(other, "subtraction");
        boolean complex = isComplex() || other.isComplex();
        double[] outReal = new double[real.length];
        double[] outImag = complex ? new double[real.length] : null;
        for (int i = 0; i < real.length; i++) {
            outReal[i] = real[i] - other.real[i];
            if (complex) outImag[i] = imag(i) - other.imag(i);
        }
        return new OresMatrix(rows, cols, outReal, outImag, false);
    }

    public OresMatrix matmul(OresMatrix other) {
        if (cols != other.rows) {
            throw new IllegalArgumentException(
                    "matrix multiplication shape mismatch: " + rows + "x" + cols
                            + " @ " + other.rows + "x" + other.cols);
        }
        boolean complex = isComplex() || other.isComplex();
        int outputElements = Math.multiplyExact(rows, other.cols);
        double[] outReal = new double[outputElements];
        double[] outImag = complex ? new double[outputElements] : null;

        for (int row = 0; row < rows; row++) {
            int leftBase = row * cols;
            int outBase = row * other.cols;
            for (int column = 0; column < other.cols; column++) {
                double sumReal = 0.0;
                double sumImag = 0.0;
                for (int k = 0; k < cols; k++) {
                    int li = leftBase + k;
                    int ri = k * other.cols + column;
                    double ar = real[li];
                    double ai = imag(li);
                    double br = other.real[ri];
                    double bi = other.imag(ri);
                    sumReal = Math.fma(ar, br, sumReal);
                    sumReal = Math.fma(-ai, bi, sumReal);
                    if (complex) {
                        sumImag = Math.fma(ar, bi, sumImag);
                        sumImag = Math.fma(ai, br, sumImag);
                    }
                }
                outReal[outBase + column] = sumReal;
                if (complex) outImag[outBase + column] = sumImag;
            }
        }
        return new OresMatrix(rows, other.cols, outReal, outImag, false);
    }

    public OresVector matmul(OresVector other) {
        return OresVector.fromMatrix(matmul(other.asMatrix()));
    }

    public OresMatrix scale(Object scalar) {
        OresComplex factor = OresComplex.of(scalar);
        boolean complex = isComplex() || factor.imaginary() != 0.0;
        double[] outReal = new double[real.length];
        double[] outImag = complex ? new double[real.length] : null;
        double br = factor.real();
        double bi = factor.imaginary();
        for (int i = 0; i < real.length; i++) {
            double ar = real[i];
            double ai = imag(i);
            outReal[i] = ar * br - ai * bi;
            if (complex) outImag[i] = ar * bi + ai * br;
        }
        return new OresMatrix(rows, cols, outReal, outImag, false);
    }

    public OresMatrix divide(Object scalar) {
        return scale(OresComplex.of(scalar).reciprocal());
    }

    public OresMatrix transpose() {
        double[] outReal = new double[real.length];
        double[] outImag = isComplex() ? new double[real.length] : null;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < cols; column++) {
                int from = row * cols + column;
                int to = column * rows + row;
                outReal[to] = real[from];
                if (outImag != null) outImag[to] = imaginary[from];
            }
        }
        return new OresMatrix(cols, rows, outReal, outImag, false);
    }

    public OresMatrix hermitian() {
        double[] outReal = new double[real.length];
        double[] outImag = isComplex() ? new double[real.length] : null;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < cols; column++) {
                int from = row * cols + column;
                int to = column * rows + row;
                outReal[to] = real[from];
                if (outImag != null) outImag[to] = -imaginary[from];
            }
        }
        return new OresMatrix(cols, rows, outReal, outImag, false);
    }

    public OresMatrix inverse() {
        requireSquare("inverse");
        return solve(identity(rows));
    }

    /**
     * Portable full-rank Moore-Penrose reference path.
     *
     * This intentionally fails closed for rank-deficient normal equations.
     * Accelerated/native backends may use SVD/QR but must preserve the same
     * observable Oreslang result/failure contract.
     */
    public OresMatrix pseudoInverse() {
        OresMatrix h = hermitian();
        if (rows >= cols) {
            return h.matmul(this).inverse().matmul(h);
        }
        return h.matmul(this.matmul(h).inverse());
    }

    public OresMatrix solve(OresMatrix rhs) {
        if (rows != rhs.rows) throw new IllegalArgumentException("solve requires A.rows == b.rows");
        if (rows != cols) return pseudoInverse().matmul(rhs);
        return solveSquare(rhs);
    }

    public Object determinant() {
        requireSquare("determinant");
        LuResult lu = luDecompose();
        double outReal = lu.sign;
        double outImag = 0.0;
        for (int i = 0; i < rows; i++) {
            int index = i * cols + i;
            double br = lu.real[index];
            double bi = lu.imag == null ? 0.0 : lu.imag[index];
            double nextReal = outReal * br - outImag * bi;
            double nextImag = outReal * bi + outImag * br;
            outReal = nextReal;
            outImag = nextImag;
        }
        if (!isComplex()) return outReal;
        return new OresComplex(outReal, outImag);
    }

    public Object trace() {
        double sumReal = 0.0;
        double sumImag = 0.0;
        int count = Math.min(rows, cols);
        for (int i = 0; i < count; i++) {
            int index = i * cols + i;
            sumReal += real[index];
            sumImag += imag(index);
        }
        if (!isComplex()) return sumReal;
        return new OresComplex(sumReal, sumImag);
    }

    /** Frobenius norm; stable hypot accumulation avoids avoidable overflow. */
    public double norm() {
        double norm = 0.0;
        for (int i = 0; i < real.length; i++) {
            norm = Math.hypot(norm, real[i]);
            if (isComplex()) norm = Math.hypot(norm, imaginary[i]);
        }
        return norm;
    }

    /**
     * Portable reference condition estimate using ||A||_F * ||pinv(A)||_F.
     * Native accelerators may provide a more precise 2-norm estimate.
     */
    public double condition() {
        return norm() * pseudoInverse().norm();
    }

    public List<Object> diagonal() {
        int count = Math.min(rows, cols);
        List<Object> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(get(i, i));
        return List.copyOf(result);
    }

    @Override public Iterable<?> sharedStateChildren() { return List.of(); }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("matrix[");
        for (int row = 0; row < rows; row++) {
            if (row > 0) out.append(", ");
            out.append('[');
            for (int column = 0; column < cols; column++) {
                if (column > 0) out.append(", ");
                out.append(get(row, column));
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    private OresMatrix solveSquare(OresMatrix rhs) {
        boolean complex = isComplex() || rhs.isComplex();
        int n = rows;
        int rhsCols = rhs.cols;
        double[] ar = Arrays.copyOf(real, real.length);
        double[] ai = complex ? new double[real.length] : null;
        double[] br = Arrays.copyOf(rhs.real, rhs.real.length);
        double[] bi = complex ? new double[rhs.real.length] : null;
        if (ai != null && imaginary != null) System.arraycopy(imaginary, 0, ai, 0, imaginary.length);
        if (bi != null && rhs.imaginary != null) System.arraycopy(rhs.imaginary, 0, bi, 0, rhs.imaginary.length);

        double scale = maxMagnitude(ar, ai);
        if (!Double.isFinite(scale)) {
            throw new IllegalArgumentException("matrix factorization requires finite numeric values");
        }
        double tolerance = scale * Math.ulp(1.0) * Math.max(16.0, n * 8.0);

        for (int pivot = 0; pivot < n; pivot++) {
            int best = pivot;
            double bestMagnitude = Math.hypot(
                    ar[pivot * n + pivot],
                    ai == null ? 0.0 : ai[pivot * n + pivot]);
            for (int row = pivot + 1; row < n; row++) {
                int idx = row * n + pivot;
                double magnitude = Math.hypot(ar[idx], ai == null ? 0.0 : ai[idx]);
                if (magnitude > bestMagnitude) {
                    bestMagnitude = magnitude;
                    best = row;
                }
            }
            if (bestMagnitude <= tolerance) {
                throw new IllegalArgumentException("matrix is singular or numerically rank deficient");
            }
            if (best != pivot) {
                swapRows(ar, n, best, pivot);
                if (ai != null) swapRows(ai, n, best, pivot);
                swapRows(br, rhsCols, best, pivot);
                if (bi != null) swapRows(bi, rhsCols, best, pivot);
            }

            int pivotIndex = pivot * n + pivot;
            double pr = ar[pivotIndex];
            double pi = ai == null ? 0.0 : ai[pivotIndex];

            for (int row = pivot + 1; row < n; row++) {
                int factorIndex = row * n + pivot;
                OresComplex factor = divide(
                        ar[factorIndex], ai == null ? 0.0 : ai[factorIndex], pr, pi);
                ar[factorIndex] = 0.0;
                if (ai != null) ai[factorIndex] = 0.0;

                for (int col = pivot + 1; col < n; col++) {
                    int idx = row * n + col;
                    int pidx = pivot * n + col;
                    subtractProduct(ar, ai, idx, factor.real(), factor.imaginary(),
                            ar[pidx], ai == null ? 0.0 : ai[pidx]);
                }
                for (int col = 0; col < rhsCols; col++) {
                    int idx = row * rhsCols + col;
                    int pidx = pivot * rhsCols + col;
                    subtractProduct(br, bi, idx, factor.real(), factor.imaginary(),
                            br[pidx], bi == null ? 0.0 : bi[pidx]);
                }
            }
        }

        double[] xr = new double[Math.multiplyExact(n, rhsCols)];
        double[] xi = complex ? new double[xr.length] : null;
        for (int row = n - 1; row >= 0; row--) {
            int diagonal = row * n + row;
            double dr = ar[diagonal];
            double di = ai == null ? 0.0 : ai[diagonal];
            for (int col = 0; col < rhsCols; col++) {
                double sr = br[row * rhsCols + col];
                double si = bi == null ? 0.0 : bi[row * rhsCols + col];
                for (int k = row + 1; k < n; k++) {
                    int aidx = row * n + k;
                    int xidx = k * rhsCols + col;
                    double rr = ar[aidx] * xr[xidx] - (ai == null ? 0.0 : ai[aidx]) * (xi == null ? 0.0 : xi[xidx]);
                    double ri = ar[aidx] * (xi == null ? 0.0 : xi[xidx])
                            + (ai == null ? 0.0 : ai[aidx]) * xr[xidx];
                    sr -= rr;
                    si -= ri;
                }
                OresComplex quotient = divide(sr, si, dr, di);
                int xidx = row * rhsCols + col;
                xr[xidx] = quotient.real();
                if (xi != null) xi[xidx] = quotient.imaginary();
            }
        }
        return new OresMatrix(n, rhsCols, xr, xi, false);
    }

    private LuResult luDecompose() {
        double[] ar = Arrays.copyOf(real, real.length);
        double[] ai = isComplex() ? Arrays.copyOf(imaginary, imaginary.length) : null;
        int sign = 1;
        double scale = maxMagnitude(ar, ai);
        if (!Double.isFinite(scale)) {
            throw new IllegalArgumentException("matrix factorization requires finite numeric values");
        }
        double tolerance = scale * Math.ulp(1.0) * Math.max(16.0, rows * 8.0);

        for (int pivot = 0; pivot < rows; pivot++) {
            int best = pivot;
            double bestMagnitude = Math.hypot(
                    ar[pivot * cols + pivot],
                    ai == null ? 0.0 : ai[pivot * cols + pivot]);
            for (int row = pivot + 1; row < rows; row++) {
                int idx = row * cols + pivot;
                double magnitude = Math.hypot(ar[idx], ai == null ? 0.0 : ai[idx]);
                if (magnitude > bestMagnitude) {
                    best = row;
                    bestMagnitude = magnitude;
                }
            }
            if (bestMagnitude <= tolerance) {
                return new LuResult(ar, ai, 0);
            }
            if (best != pivot) {
                swapRows(ar, cols, best, pivot);
                if (ai != null) swapRows(ai, cols, best, pivot);
                sign = -sign;
            }

            int p = pivot * cols + pivot;
            double pr = ar[p];
            double pi = ai == null ? 0.0 : ai[p];
            for (int row = pivot + 1; row < rows; row++) {
                int f = row * cols + pivot;
                OresComplex factor = divide(ar[f], ai == null ? 0.0 : ai[f], pr, pi);
                ar[f] = factor.real();
                if (ai != null) ai[f] = factor.imaginary();
                for (int col = pivot + 1; col < cols; col++) {
                    int idx = row * cols + col;
                    int pidx = pivot * cols + col;
                    subtractProduct(ar, ai, idx, factor.real(), factor.imaginary(),
                            ar[pidx], ai == null ? 0.0 : ai[pidx]);
                }
            }
        }
        return new LuResult(ar, ai, sign);
    }

    private int index(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= cols) {
            throw new IndexOutOfBoundsException(
                    "matrix index (" + row + "," + column + ") outside " + rows + "x" + cols);
        }
        return row * cols + column;
    }

    private double imag(int index) {
        return imaginary == null ? 0.0 : imaginary[index];
    }

    private void requireSameShape(OresMatrix other, String operation) {
        if (rows != other.rows || cols != other.cols) {
            throw new IllegalArgumentException(operation + " requires equal matrix shapes");
        }
    }

    private void requireSquare(String operation) {
        if (rows != cols) throw new IllegalArgumentException(operation + " requires a square matrix");
    }

    private static void requireScalar(Object value) {
        if (value instanceof BigDecimal) {
            throw new IllegalArgumentException(
                    "decimal matrix elements require a separate exact backend; refusing silent decimal -> f64 coercion");
        }
        if (!(value instanceof Number) && !(value instanceof OresComplex)) {
            throw new IllegalArgumentException("matrix elements must be real or complex numbers");
        }
    }

    private static double maxMagnitude(double[] real, double[] imaginary) {
        double max = 0.0;
        for (int i = 0; i < real.length; i++) {
            max = Math.max(max, Math.hypot(real[i], imaginary == null ? 0.0 : imaginary[i]));
        }
        return max;
    }

    private static void swapRows(double[] values, int width, int a, int b) {
        if (a == b) return;
        int aBase = a * width;
        int bBase = b * width;
        for (int col = 0; col < width; col++) {
            double tmp = values[aBase + col];
            values[aBase + col] = values[bBase + col];
            values[bBase + col] = tmp;
        }
    }

    private static void subtractProduct(
            double[] real,
            double[] imaginary,
            int index,
            double ar,
            double ai,
            double br,
            double bi) {
        real[index] -= ar * br - ai * bi;
        if (imaginary != null) imaginary[index] -= ar * bi + ai * br;
    }

    private static OresComplex divide(double ar, double ai, double br, double bi) {
        return new OresComplex(ar, ai).divide(new OresComplex(br, bi));
    }

    private record LuResult(double[] real, double[] imag, int sign) { }
}
