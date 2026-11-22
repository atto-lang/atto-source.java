package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the proposed numerical v1 contract against real Java/Graal guest code. */
final class NumericCheckedI64CurrentMainTest {
    private static long run(String source) {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(OresLanguage.ID, source).asLong();
        }
    }

    @Test
    void ordinaryGuestArithmeticOverflowIsCaughtNominally() {
        String[][] cases = {
                {"lhs + rhs", "9223372036854775807", "1"},
                {"lhs - rhs", "-9223372036854775808", "1"},
                {"lhs * rhs", "3037000500", "3037000500"},
                {"-lhs", "-9223372036854775808", "0"}
        };
        for (String[] c : cases) {
            String program = """
                    pub fnc checked(int lhs, int rhs): int { return %s; }
                    pub fnc main(): int {
                      try {
                        return checked(%s, %s);
                      } catch (err) {
                        if err is type ArithmeticOverflowError {
                          return 42;
                        }
                        return 1;
                      } finally {
                      }
                    }
                    """.formatted(c[0], c[1], c[2]);
            assertEquals(42L, run(program), c[0]);
        }
    }

    @Test
    void trapOfOverflowIsResultErrAndNormalArithmeticIsResultOk() {
        String program = """
                pub fnc checked(int lhs, int rhs): int { return lhs + rhs; }
                pub fnc main(): int {
                  val failed = trap checked(9223372036854775807, 1);
                  val valid = trap checked(40, 2);
                  if failed.is_err() && valid.is_ok() then
                    return valid.unwrap();
                  fi
                  return 1;
                }
                """;
        assertEquals(42L, run(program));
    }

    @Test
    void integerDivisionHasIntegerReturnTypeEvenWithNamedRuntimeOperands() {
        String source = """
                pub fnc quotient(int lhs, int rhs): int { return lhs / rhs; }
                pub fnc main(): int {
                  val int left = -7;
                  val int right = 3;
                  return quotient(left, right);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals(-2L, run(source));
    }
}
