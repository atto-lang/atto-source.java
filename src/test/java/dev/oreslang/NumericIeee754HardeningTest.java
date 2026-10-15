package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** IEEE-754 guest arithmetic, literal admission, and mixed-domain comparison regressions. */
final class NumericIeee754HardeningTest {
    @Test
    void finiteBinary64LiteralsAndSeparators() {
        Ast.LiteralExpr value = assertInstanceOf(Ast.LiteralExpr.class,
                new Parser(new Lexer("1_0.2_5e+1").scan()).parseExpression());
        assertEquals(102.5, assertInstanceOf(Double.class, value.value()).doubleValue());
        assertEquals(Double.MIN_VALUE,
                assertInstanceOf(Double.class, assertInstanceOf(Ast.LiteralExpr.class,
                        new Parser(new Lexer("5e-324").scan()).parseExpression()).value()).doubleValue());
    }

    @Test
    void rejectsMalformedAndOverflowingBinary64Literals() {
        for (String source : new String[]{
                "1__0.25", "1_.0", "1.0__2", "1e3_", "1e9999", "1e9999i", "1__0i"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new Parser(new Lexer(source).scan()).parseExpression(), source);
            assertFalse(error.getMessage().isBlank(), source);
        }
    }

    @Test
    void nanIsUnorderedAndSignedZeroIsNumericallyEqual() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val float nan = 0.0 / 0.0;
                      val float positiveZero = 0.0;
                      val float negativeZero = -0.0;
                      return !(nan eq nan)
                        && (nan neq nan)
                        && !(nan < 1.0)
                        && !(nan <= 1.0)
                        && !(nan > 1.0)
                        && !(nan >= 1.0)
                        && !(1.0 < nan)
                        && !(1.0 <= nan)
                        && !(1.0 > nan)
                        && !(1.0 >= nan)
                        && (positiveZero eq negativeZero)
                        && !(negativeZero < positiveZero)
                        && !(negativeZero > positiveZero)
                        && (negativeZero <= positiveZero)
                        && (negativeZero >= positiveZero);
                    }
                    """).asBoolean());
        }
    }

    @Test
    void sameComplexInstanceContainingNaNIsNotEqualToItself() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val complex invalid = 0.0i / 0.0i;
                      return !(invalid eq invalid);
                    }
                    """).asBoolean());
        }
    }

    @Test
    void mixedInt64AndBinary64ComparisonsKeepExactIntegerOrder() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      return 9007199254740993 > 9007199254740992.0
                        && 9007199254740992.0 < 9007199254740993
                        && 9007199254740992 == 9007199254740992.0
                        && 9223372036854775807 < 9223372036854775808.0
                        && -9223372036854775807 > -9223372036854775808.0;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void complexDivisionRecoversSmallRepresentableComponentAcrossExponentGaps() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val complex quotient =
                          (-3.8267306126191597e229 - 2.653330234432702e-108i)
                            / (4.996866008987324e199 + 5.230549533052834e-126i);
                      return quotient eq (-7.658261409724479e29 + 8.016407798119527e-296i);
                    }
                    """).asBoolean());
        }
    }

    @Test
    void complexDivisionAvoidsIntermediateOverflowAndUnderflow() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      return (1e308i / 1e308i) eq 1
                        && (1e-300i / 1e-300i) eq 1
                        && ((1e308 + 1e308i) / (1e308 + 1e308i)) eq 1
                        && ((1e-300 + 1e-300i) / (1e-300 + 1e-300i)) eq 1
                        && (5e-324i / 5e-324i) eq 1
                        && ((1e308 + 1e308i) / (1e-308 + 1e-308i)) eq (1e308 / 1e-308);
                    }
                    """).asBoolean());
        }
    }
}
