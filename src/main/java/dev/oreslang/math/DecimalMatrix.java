package dev.oreslang.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded, host-internal decimal matrix reference implementation.
 *
 * <p>All input decimal strings are parsed without binary floating-point conversion.
 * Multiplication sums exact decimal products and rounds each result exactly once
 * using the explicit context. This is not yet the guest Matrix<T> API, nor does
 * it promise zero allocation, concurrent mutation safety, or LLVM parity.
 */
public final class DecimalMatrix {
    private static final int MAX_CELLS = 65_536;
    private static final int MAX_DIMENSION = 65_536;
    private static final int MAX_DIGITS_PER_CELL = 4_096;
    private static final int MAX_ABSOLUTE_SCALE = 16_384;
    private static final long MAX_SCALAR_MULTIPLIES = 100_000;
    private final int rows;
    private final int columns;
    private final BigDecimal[] cells;

    private DecimalMatrix(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;
        this.cells = new BigDecimal[checkedCells(rows, columns)];
        Arrays.fill(cells, BigDecimal.ZERO);
    }

    public static DecimalMatrix zeros(int rows, int columns) {
        return new DecimalMatrix(rows, columns);
    }

    /** Row-major exact decimal strings. A zero-row shape needs explicit columns. */
    public static DecimalMatrix fromStrings(int rows, int columns, String... values) {
        DecimalMatrix matrix = zeros(rows, columns);
        Objects.requireNonNull(values, "values");
        if (values.length != matrix.cells.length) {
            throw new IllegalArgumentException("matrix cell count mismatches shape");
        }
        for (int i = 0; i < values.length; i++) {
            BigDecimal value = new BigDecimal(Objects.requireNonNull(values[i], "decimal cell"));
            validateDecimal(value);
            matrix.cells[i] = value;
        }
        return matrix;
    }

    public int rows() { return rows; }
    public int columns() { return columns; }

    public BigDecimal get(int row, int column) {
        return cells[index(row, column)];
    }

    /**
     * A fresh owned matrix is always returned. An explicit context with
     * precision=0 computes exact decimal results without rounding.
     */
    public static DecimalMatrix multiply(
            DecimalMatrix left, DecimalMatrix right, MathContext context) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        DecimalMatrix result = zeros(left.rows, right.columns);
        multiplyInto(result, left, right, context);
        return result;
    }

    /**
     * Writes into a caller-owned result. In-place aliasing is rejected because
     * a column or row read could be overwritten before multiplication ends.
     * The destination must not be accessed concurrently during this operation.
     * Temporary BigDecimal objects may allocate; no zero-allocation claim.
     */
    public static void multiplyInto(
            DecimalMatrix output, DecimalMatrix left, DecimalMatrix right,
            MathContext context) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(context, "context");
        if (left.columns != right.rows || output.rows != left.rows
                || output.columns != right.columns) {
            throw new IllegalArgumentException("matrix shape mismatch");
        }
        if ((long) left.rows * left.columns * right.columns > MAX_SCALAR_MULTIPLIES) {
            throw new IllegalArgumentException("matrix arithmetic budget exceeded");
        }
        if (output == left || output == right) {
            throw new IllegalArgumentException("output must not alias inputs");
        }
        // Stage results until every destination cell passes resource checks.
        // Failure must not publish a partially updated caller-owned matrix.
        BigDecimal[] staged = new BigDecimal[output.cells.length];
        for (int row = 0; row < left.rows; row++) {
            for (int column = 0; column < right.columns; column++) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int inner = 0; inner < left.columns; inner++) {
                    BigDecimal product = left.get(row, inner).multiply(right.get(inner, column));
                    sum = sum.add(product);
                }
                BigDecimal rounded = sum.round(context);
                validateDecimal(rounded);
                staged[output.index(row, column)] = rounded;
            }
        }
        System.arraycopy(staged, 0, output.cells, 0, staged.length);
    }

    private int index(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException("matrix index outside shape");
        }
        return row * columns + column;
    }

    private static void validateDecimal(BigDecimal value) {
        if (value.precision() > MAX_DIGITS_PER_CELL
                || Math.abs((long) value.scale()) > MAX_ABSOLUTE_SCALE) {
            throw new IllegalArgumentException("decimal precision or exponent budget exceeded");
        }
    }

    private static int checkedCells(int rows, int columns) {
        if (rows < 0 || columns < 0) {
            throw new IllegalArgumentException("negative matrix dimension");
        }
        if (rows > MAX_DIMENSION || columns > MAX_DIMENSION) {
            throw new IllegalArgumentException("matrix dimension budget exceeded");
        }
        if (rows != 0 && columns > MAX_CELLS / rows) {
            throw new IllegalArgumentException("matrix allocation budget exceeded");
        }
        return rows * columns;
    }
}
