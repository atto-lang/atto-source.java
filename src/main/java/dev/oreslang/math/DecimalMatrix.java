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
    // A per-cell cap alone permits 65,536 independent 4,096-digit values.
    // Bound total significant-digit storage per matrix as well.
    private static final long MAX_TOTAL_SIGNIFICANT_DIGITS = 1_000_000L;
    private static final int MAX_ABSOLUTE_SCALE = 16_384;
    private static final long MAX_SCALAR_MULTIPLIES = 100_000;
    // Work varies dramatically with decimal limb sizes even at the same
    // matrix dimensions. Bound digit-work and temporary alignment separately.
    private static final long MAX_DIGIT_WORK = 16_000_000L;
    private static final long MAX_INTERMEDIATE_DIGITS = 16_384L;
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
        long significantDigits = 0;
        for (int i = 0; i < values.length; i++) {
            String literal = Objects.requireNonNull(values[i], "decimal cell");
            // Reject excessively long input before BigDecimal(String) allocates.
            if (literal.length() > MAX_DIGITS_PER_CELL + 32) {
                throw new IllegalArgumentException("decimal literal budget exceeded");
            }
            BigDecimal value = new BigDecimal(literal);
            validateDecimal(value);
            significantDigits += value.precision();
            if (significantDigits > MAX_TOTAL_SIGNIFICANT_DIGITS) {
                throw new IllegalArgumentException("matrix significant-digit budget exceeded");
            }
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
        validateContext(Objects.requireNonNull(context, "context"));
        checkMultiplyShapesAndWork(left.rows, left.columns, right.rows, right.columns,
                left.rows, right.columns);
        checkDigitWorkAdmission(left, right);
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
        validateContext(Objects.requireNonNull(context, "context"));
        checkMultiplyShapesAndWork(left.rows, left.columns, right.rows, right.columns,
                output.rows, output.columns);
        if (output == left || output == right) {
            throw new IllegalArgumentException("output must not alias inputs");
        }
        checkDigitWorkAdmission(left, right);
        // Stage results until every destination cell passes resource checks.
        // Failure must not publish a partially updated caller-owned matrix.
        BigDecimal[] staged = new BigDecimal[output.cells.length];
        long significantDigits = 0;
        long digitWork = 0;
        for (int row = 0; row < left.rows; row++) {
            for (int column = 0; column < right.columns; column++) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int inner = 0; inner < left.columns; inner++) {
                    BigDecimal a = left.get(row, inner);
                    BigDecimal b = right.get(inner, column);
                    // BigDecimal product precision is at most p(a)+p(b).
                    // Reject expensive jobs before constructing the product.
                    long productDigits = (long) a.precision() + b.precision();
                    digitWork += productDigits;
                    if (digitWork > MAX_DIGIT_WORK) {
                        throw new IllegalArgumentException("matrix decimal digit-work budget exceeded");
                    }
                    BigDecimal product = a.multiply(b);
                    if (sum.signum() != 0 && product.signum() != 0) {
                        int alignedScale = Math.max(sum.scale(), product.scale());
                        long alignedDigits = Math.max(
                                (long) sum.precision() + alignedScale - sum.scale(),
                                (long) product.precision() + alignedScale - product.scale());
                        if (alignedDigits > MAX_INTERMEDIATE_DIGITS) {
                            throw new IllegalArgumentException(
                                    "matrix decimal accumulator alignment budget exceeded");
                        }
                    }
                    sum = sum.add(product);
                }
                BigDecimal rounded = sum.round(context);
                validateDecimal(rounded);
                significantDigits += rounded.precision();
                if (significantDigits > MAX_TOTAL_SIGNIFICANT_DIGITS) {
                    throw new IllegalArgumentException("matrix significant-digit budget exceeded");
                }
                staged[output.index(row, column)] = rounded;
            }
        }
        System.arraycopy(staged, 0, output.cells, 0, staged.length);
    }

    private static void checkMultiplyShapesAndWork(
            int leftRows, int inner, int rightRows, int rightColumns,
            int outputRows, int outputColumns) {
        if (inner != rightRows || outputRows != leftRows || outputColumns != rightColumns) {
            throw new IllegalArgumentException("matrix shape mismatch");
        }
        if ((long) leftRows * inner * rightColumns > MAX_SCALAR_MULTIPLIES) {
            throw new IllegalArgumentException("matrix arithmetic budget exceeded");
        }
    }

    private static void checkDigitWorkAdmission(DecimalMatrix left, DecimalMatrix right) {
        // Worst-case complexity based on maximum input limb widths. This is a
        // conservative rejection gate, deliberately evaluated *before* output
        // allocation or the first BigDecimal multiplication.
        long leftDigits = 0, rightDigits = 0;
        for (BigDecimal value : left.cells) {
            leftDigits = Math.max(leftDigits, value.precision());
        }
        for (BigDecimal value : right.cells) {
            rightDigits = Math.max(rightDigits, value.precision());
        }
        long operations = (long) left.rows * left.columns * right.columns;
        if (operations * (leftDigits + rightDigits) > MAX_DIGIT_WORK) {
            throw new IllegalArgumentException("matrix decimal digit-work budget exceeded");
        }
    }

    private int index(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException("matrix index outside shape");
        }
        return row * columns + column;
    }

    private static void validateContext(MathContext context) {
        // Zero precision explicitly requests exact arithmetic. Any positive
        // precision must still stay within the output digit allocation budget.
        if (context.getPrecision() > MAX_DIGITS_PER_CELL) {
            throw new IllegalArgumentException("matrix rounding precision budget exceeded");
        }
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
