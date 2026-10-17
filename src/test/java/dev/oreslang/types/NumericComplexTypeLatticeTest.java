package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NumericComplexTypeLatticeTest {
    @Test
    void decimalNeverImplicitlyNarrowsIntoBinary64Complex() {
        assertFalse(Types.isAssignable(
                Types.Primitive.DECIMAL, Types.Primitive.COMPLEX));
        assertSame(Types.Unknown.INSTANCE,
                Types.numericJoin(
                        Types.Primitive.DECIMAL, Types.Primitive.COMPLEX));
        assertSame(Types.Unknown.INSTANCE,
                Types.numericJoin(
                        Types.Primitive.COMPLEX, Types.Primitive.DECIMAL));
    }

    @Test
    void safeScalarWideningsRemainAvailable() {
        assertTrue(Types.isAssignable(
                Types.Primitive.INT, Types.Primitive.COMPLEX));
        assertTrue(Types.isAssignable(
                Types.Primitive.FLOAT, Types.Primitive.COMPLEX));
    }
}
