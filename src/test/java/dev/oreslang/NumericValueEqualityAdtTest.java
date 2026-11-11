package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.NumericValueEquality;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same predicate is used by static ADT pattern subsumption and by
 * runtime Option/Result/structural value equality. These are host-unit
 * invariants, not evidence of portable guest compiler execution.
 */
final class NumericValueEqualityAdtTest {
    @Test
    void arbitraryPrecisionCannotAliasFloatingInfinity() {
        BigInteger enormous = BigInteger.ONE.shiftLeft(16384);
        assertTrue(Double.isInfinite(enormous.doubleValue()),
                "the test must exercise a lossy infinite double conversion");
        assertFalse(NumericValueEquality.equals(enormous, Double.POSITIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.POSITIVE_INFINITY, enormous));
        assertFalse(NumericValueEquality.equals(enormous.negate(), Double.NEGATIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.NEGATIVE_INFINITY, enormous.negate()));
        assertFalse(NumericValueEquality.equals(new BigDecimal(enormous), Double.POSITIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.NEGATIVE_INFINITY, new BigDecimal(enormous.negate())));
    }

    @Test
    void finiteAndNonfiniteValuesKeepExpectedEquality() {
        assertTrue(NumericValueEquality.equals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertTrue(NumericValueEquality.equals(Double.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.NaN, Double.NaN));
        assertFalse(NumericValueEquality.equals(new BigDecimal("0"), Double.NaN));
        assertFalse(NumericValueEquality.equals(new BigInteger("0"), Double.POSITIVE_INFINITY));
        assertTrue(NumericValueEquality.equals(-0.0d, +0.0d));
    }

    @Test
    void exactFiniteCrossRepresentationEqualityAndRoundingBoundary() {
        assertTrue(NumericValueEquality.equals(1, 1.0d));
        assertTrue(NumericValueEquality.equals(new BigInteger("9007199254740993"),
                new BigDecimal("9007199254740993.000")));
        assertFalse(NumericValueEquality.equals(9007199254740993L, 9007199254740992.0d));
        assertFalse(NumericValueEquality.equals(new BigInteger("9007199254740993"),
                9007199254740992.0d));
        assertFalse(NumericValueEquality.equals(42L, 42.5d));
    }

    @Test
    void nestedSumValueEqualityExecutesInActualAttoGuest() throws Exception {
        String program;
        try (InputStream in = getClass().getResourceAsStream(
                "/adt-parity/numeric-sum-value-equality.atto")) {
            assertNotNull(in, "missing canonical AttoLang guest fixture");
            program = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "numeric-sum-value-equality.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("true\ntrue\ntrue\ntrue\n",
                output.toString(StandardCharsets.UTF_8));
    }

}
