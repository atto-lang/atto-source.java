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
        assertEquals(102.5, assertInstanceOf(Double.class, value.value()));
        assertEquals(Double.MIN_VALUE,
                assertInstanceOf(Double.class, assertInstanceOf(Ast.LiteralExpr.class,
                        new Parser(new Lexer("5e-324").scan()).parseExpression()).value()));
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
}
