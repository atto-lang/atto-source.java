package dev.oreslang.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
