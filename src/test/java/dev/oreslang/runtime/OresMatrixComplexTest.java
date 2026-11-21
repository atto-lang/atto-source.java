package dev.oreslang.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OresMatrixComplexTest {
    @Test
    void pinnedComplexMatrixVectorUsesExactWholeDotAccumulation() {
        OresMatrix left = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(1.0, 2.0), new OresComplex(3.0, -1.0))));
        OresMatrix right = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(2.0, -1.0)),
                List.of(new OresComplex(4.0, 3.0))));

        OresComplex result =
                assertInstanceOf(OresComplex.class, left.matmul(right).get(0, 0));
        assertEquals(
                Double.doubleToRawLongBits(19.0),
                Double.doubleToRawLongBits(result.real()));
        assertEquals(
                Double.doubleToRawLongBits(8.0),
                Double.doubleToRawLongBits(result.imaginary()));
    }

    @Test
    void oneByOneMatrixUsesSameSubnormalMultiplyContractAsScalar() {
        OresMatrix left = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(1.3e-162, 1.3e-162))));
        OresMatrix right = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(1.3e-162, 1.3e-162))));
        OresComplex result =
                assertInstanceOf(OresComplex.class, left.matmul(right).get(0, 0));
        assertEquals(0L, Double.doubleToRawLongBits(result.real()));
        assertEquals(1L, Double.doubleToRawLongBits(result.imaginary()));
    }

    @Test
    void immutableMatrixAndVectorAreAuditedActorDataValues() {
        OresMatrix matrix = OresMatrix.fromRows(List.of(
                List.of(1.0, 2.0),
                List.of(3.0, 4.0)));
        OresVector vector = OresVector.fromValues(List.of(5.0, 6.0));
        assertSame(matrix, ActorRuntime.freeze(matrix));
        assertSame(vector, ActorRuntime.freeze(vector));
        assertEquals(2, matrix.rows());
        assertEquals(2, vector.length());
    }

    @Test
    void exactDomainsDoNotImplicitlyNarrowIntoDenseBinary64Matrices() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresMatrix.fromRows(List.of(
                        List.of(new java.math.BigDecimal("0.1")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresVector.fromValues(List.of(
                        new java.math.BigInteger("9007199254740993"))));
    }

    @Test
    void nonfiniteComplexDotFailsClosedUntilMatrixExceptionalPolicyExists() {
        OresMatrix left = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(Double.POSITIVE_INFINITY, 0.0))));
        OresMatrix right = OresMatrix.fromRows(List.of(
                List.of(new OresComplex(1.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> left.matmul(right));
    }

    @Test
    void oversizedMatricesFailBeforeLargeAllocationOrExactWork() {
        // 2048^2 doubles already consume the full 32 MiB reference budget
        // before object/array overhead; reject before allocating that plane.
        assertThrows(IllegalArgumentException.class, () -> OresMatrix.identity(2048));

        OresMatrix left = OresMatrix.fromRows(
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(r -> java.util.stream.IntStream.range(0, 101)
                                .mapToObj(c -> 1.0)
                                .toList())
                        .toList());
        OresMatrix right = OresMatrix.fromRows(
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(r -> java.util.stream.IntStream.range(0, 101)
                                .mapToObj(c -> 1.0)
                                .toList())
                        .toList());

        // 101*101*101 = 1,030,301 exact term products: modest input
        // allocations, but beyond the portable BigInteger reference budget.
        assertThrows(IllegalArgumentException.class, () -> left.matmul(right));
    }

    @Test
    void zeroImaginaryComplexScalarsPreserveComplexMatrixAndVectorElementTypes() {
        OresMatrix real = OresMatrix.fromRows(List.of(List.of(2.0)));
        for (OresComplex factor : List.of(
                new OresComplex(2.0, +0.0),
                new OresComplex(2.0, -0.0))) {
            OresMatrix scaled = real.scale(factor);
            OresMatrix divided = real.divide(factor);
            assertTrue(scaled.isComplex());
            assertTrue(divided.isComplex());
            assertInstanceOf(OresComplex.class, scaled.get(0, 0));
            assertInstanceOf(OresComplex.class, divided.get(0, 0));
            assertInstanceOf(OresComplex.class, scaled.trace());
            assertInstanceOf(OresComplex.class, divided.trace());
            assertInstanceOf(OresComplex.class, scaled.hermitian().get(0, 0));

            OresVector vector = OresVector.fromValues(List.of(2.0));
            assertInstanceOf(OresComplex.class, vector.scale(factor).get(0));
            assertInstanceOf(OresComplex.class, vector.divide(factor).get(0));
        }
    }

    @Test
    void impossibleLiteralShapeFailsBeforeTraversingGuestProvidedRows() {
        // The list advertises >32 MiB of real cells without materializing them.
        // Accessing row 1 would be a correctness failure: preflight must reject
        // the shape immediately after inspecting the first row.
        List<Object> oversizedRows = new java.util.AbstractList<>() {
            @Override
            public Object get(int index) {
                if (index != 0) throw new AssertionError(
                        "oversized matrix was traversed before allocation preflight");
                return List.of(1.0);
            }

            @Override
            public int size() {
                return 4_194_297;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> OresMatrix.fromRows(oversizedRows));
    }

    @Test
    void outerHermitianPreservesComplexConjugationWithoutTemporaryCellGraph() {
        OresVector left = OresVector.fromValues(List.of(
                1.0,
                new OresComplex(0.0, 2.0)));
        OresVector right = OresVector.fromValues(List.of(
                new OresComplex(3.0, 4.0)));
        OresMatrix out = left.outerHermitian(right);
        assertEquals(2, out.rows());
        assertEquals(1, out.cols());
        assertEquals(new OresComplex(3.0, -4.0), out.get(0, 0));
        assertEquals(new OresComplex(8.0, 6.0), out.get(1, 0));
        assertTrue(out.isComplex());
    }

    @Test
    void outerHermitianRejectsOversizedExactWorkBeforeAllocatingOutput() {
        OresVector thousandAndOne = OresVector.fromValues(
                java.util.stream.IntStream.range(0, 1001)
                        .mapToObj(i -> 1.0)
                        .toList());
        // 1001*1001 individual exact multiplications exceed the 1,000,000
        // reference work budget even though the final backing arrays fit.
        assertThrows(IllegalArgumentException.class,
                () -> thousandAndOne.outerHermitian(thousandAndOne));
    }

    @Test
    void complexScalarPromotionRejectsOverBudgetOutputShape() {
        // 1450^2 real doubles fit in 32 MiB, but the 16-byte complex result
        // does not. Promotion must preflight before allocating either plane.
        OresMatrix real = OresMatrix.identity(1450);
        OresComplex typedReal = new OresComplex(1.0, 0.0);
        assertThrows(IllegalArgumentException.class, () -> real.scale(typedReal));
        assertThrows(IllegalArgumentException.class, () -> real.divide(typedReal));
    }

}
