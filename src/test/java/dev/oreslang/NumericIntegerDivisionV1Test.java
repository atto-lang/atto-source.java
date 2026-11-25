package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.List;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AttoLang numerical semantics v1: strict integer division is checked and
 * truncated toward zero. These are real guest-language tests, not a host
 * arithmetic reference, and must execute on the exact PR head before merge.
 */
final class NumericIntegerDivisionV1Test {
    @Test
    void typeCheckerPreservesI64DivisionInsteadOfWideningToFloat() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc quotient(int a, int b): int { return a / b; }
                pub fnc main(): int { return quotient(-84, 2); }
                """)));
    }

    @Test
    void signedQuotientsTruncateTowardZeroAcrossFunctionBoundary() {
        String[][] cases = {
                {"-7", "3", "-2"}, {"7", "-3", "-2"}, {"-7", "-3", "2"},
                {"7", "3", "2"}, {"1", "2", "0"}, {"-1", "2", "0"},
                {"-9223372036854775808", "1", "-9223372036854775808"},
                {"9223372036854775807", "1", "9223372036854775807"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc quotient(int a, int b): int { return a / b; }
                    pub fnc main(): int { return quotient(%s, %s); }
                    """.formatted(testCase[0], testCase[1]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(Long.parseLong(testCase[2]),
                        context.eval(OresLanguage.ID, guest).asLong(),
                        testCase[0] + " / " + testCase[1]);
            }
        }
    }

    @Test
    void remainderKeepsDividendSignAndMinRemainderMinusOneIsZero() {
        String[][] cases = {
                {"-7", "3", "-1"}, {"7", "-3", "1"}, {"-7", "-3", "-1"},
                {"7", "3", "1"}, {"-9223372036854775808", "-1", "0"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc remainder(int a, int b): int { return Integer.rem(a, b); }
                    pub fnc main(): int { return remainder(%s, %s); }
                    """.formatted(testCase[0], testCase[1]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(Long.parseLong(testCase[2]),
                        context.eval(OresLanguage.ID, guest).asLong(),
                        testCase[0] + " rem " + testCase[1]);
            }
        }
    }

    @Test
    void removedPercentRejectedAtSourceAndDirectAstBoundaries() {
        for (String expression : List.of("7 % 3", "7.0 % 3.0", "7%3", "7 % -3")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): int { return " + expression + "; }"),
                    expression);
            assertTrue(error.getMessage().contains("Integer.rem"), error::getMessage);
        }

        // Construct a direct AST rather than relying on the lexer/parser to
        // reject a removed operator before the type checker ever sees it.
        Ast.Program parsed = Parser.parse("pub fnc main(): int { return 7 + 3; }");
        Ast.ModuleDecl module = parsed.modules().getFirst();
        Ast.FunctionDecl original = (Ast.FunctionDecl) module.declarations().getFirst();
        Ast.FunctionDecl injected = new Ast.FunctionDecl(
                original.name(), original.kind(), original.visibility(), original.async(),
                original.suspends(), original.generator(), original.structural(),
                original.nonLexical(), original.trapped(), original.actorKind(),
                original.genericParameters(), original.parameters(), original.returnType(),
                original.annotations(), List.of(new Ast.ReturnStmt(new Ast.BinaryExpr("%",
                        new Ast.LiteralExpr(7L), new Ast.LiteralExpr(3L)))),
                original.genericSpecs(), original.effects());
        Ast.Program invalid = new Ast.Program(parsed.namespace(), parsed.imports(),
                List.of(new Ast.ModuleDecl(module.name(), module.annotations(),
                        module.contracts(), List.of(injected))), parsed.exports());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(invalid));
        assertTrue(error.getMessage().contains("E_OPERATOR_PERCENT"), error::getMessage);
    }

    @Test
    void euclideanModulusHandlesSignedDivisorsAndMinWithoutAbsOverflow() {
        String[][] cases = {
                {"-7", "3", "2"}, {"-7", "-3", "2"},
                {"7", "-3", "1"}, {"7", "3", "1"},
                {"-9223372036854775808", "-1", "0"},
                {"-1", "-9223372036854775808", "9223372036854775807"},
                {"-9223372036854775808", "-9223372036854775808", "0"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc modulo(int a, int b): int { return Integer.mod(a, b); }
                    pub fnc main(): int { return modulo(%s, %s); }
                    """.formatted(testCase[0], testCase[1]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(Long.parseLong(testCase[2]),
                        context.eval(OresLanguage.ID, guest).asLong(),
                        testCase[0] + " mod " + testCase[1]);
            }
        }
    }

    @Test
    void zeroDivisorAndZeroRemainderHaveGuestErrorIdentity() {
        for (String expression : new String[] { "a / b", "Integer.rem(a, b)", "Integer.mod(a, b)" }) {
            String guest = """
                    pub fnc checked(int a, int b): int { return %s; }
                    pub fnc main(): int {
                      try {
                        return checked(7, 0);
                      } catch (err) {
                        if err is type DivisionByZeroError then
                          if err is type Error then return 42; fi
                          return 2;
                        fi
                        return 1;
                      } finally {
                      }
                    }
                    """.formatted(expression);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong(),
                        "guest error identity lost for " + expression);
            }
        }
    }

    @Test
    void signedMinDividedByNegativeOneRaisesOverflowNotZeroDivisor() {
        String guest = """
                pub fnc checked(int a, int b): int { return a / b; }
                pub fnc main(): int {
                  try {
                    return checked(-9223372036854775808, -1);
                  } catch (err) {
                    if err is type ArithmeticOverflowError then return 42; fi
                    return 1;
                  } finally {
                  }
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void uncaughtZeroDivisorIsGuestErrorNotHostArithmeticException() {
        String guest = """
                pub fnc main(): int {
                  val int divisor = 0;
                  return 7 / divisor;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, guest));
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains("DivisionByZeroError"),
                    failure::getMessage);
            assertFalse(failure.getMessage().contains("java.lang.ArithmeticException"),
                    failure::getMessage);
        }
    }

    @Test
    void floatingDivisionRemainsBinaryFloatingDivision() {
        String guest = """
                pub fnc quotient(float a, float b): float { return a / b; }
                pub fnc main(): float { return quotient(7.0, 2.0); }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(3.5d, context.eval(OresLanguage.ID, guest).asDouble());
        }
    }
}
