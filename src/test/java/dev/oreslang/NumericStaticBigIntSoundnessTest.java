package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Primitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BigInt is a distinct static domain: it may widen, never narrow to i64/f64. */
final class NumericStaticBigIntSoundnessTest {
    @Test
    void primitiveBigIntHasDistinctIdentityAndExactWidening() {
        assertNotEquals(Primitive.INT, Primitive.BIGINT);
        assertTrue(Types.isNumeric(Primitive.BIGINT));
        assertTrue(Types.isAssignable(Primitive.INT, Primitive.BIGINT));
        assertFalse(Types.isAssignable(Primitive.BIGINT, Primitive.INT));
        assertFalse(Types.isAssignable(Primitive.BIGINT, Primitive.FLOAT));
        assertTrue(Types.isAssignable(Primitive.BIGINT, Primitive.DECIMAL));
        assertEquals(Primitive.BIGINT, Types.numericJoin(Primitive.INT, Primitive.BIGINT));
        assertEquals(Primitive.DECIMAL, Types.numericJoin(Primitive.BIGINT, Primitive.DECIMAL));
        assertEquals(Types.Unknown.INSTANCE, Types.numericJoin(Primitive.BIGINT, Primitive.FLOAT));
        assertEquals(Types.Unknown.INSTANCE, Types.numericJoin(Primitive.DECIMAL, Primitive.FLOAT));
    }

    @Test
    void doesNotPermitBigIntArithmeticResultToNarrowIntoFixedWidthInt() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc illegal(bigint big): int {
                  return big + 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc illegal(bigint big): float {
                  return big + 1;
                }
                """)));
    }

    @Test
    void doesNotInferBinary64ForBigIntDivisionOrMixedArithmetic() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc illegal(bigint big): bigint {
                  return big / 2;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc illegal(bigint big, float fraction): bigint {
                  return big + fraction;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc illegal(bigint big, float fraction): float {
                  return fraction * big;
                }
                """)));
    }

    @Test
    void complexOrderingIsRejectedAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(complex a, complex b): bool {
                  return a < b;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(complex a, int b): bool {
                  return a >= b;
                }
                """)));
    }

    @Test
    void permitsIntegerWideningAndExactBigIntArithmetic() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc increment(bigint big): bigint {
                  return big + 1;
                }
                fnc promote(int fixed): bigint {
                  return fixed;
                }
                fnc decimalFromBigInt(bigint big): decimal {
                  return big + 1;
                }
                """)));
    }
}
