package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ImplicitConditionalReturnTest {
    private static long evaluate(String source) {
        TypeChecker.check(Parser.parse(source));
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return context.eval(OresLanguage.ID, source).asLong();
        }
    }

    private static final String PREFIX = """
            pub fnc checked_add(int lhs, int rhs): int {
              return lhs + rhs;
            }
            pub fnc main(): int {
              try {
                return checked_add(9223372036854775807, 1);
              } catch (err) {
            """;

    private static final String SUFFIX = """
              } finally {
              }
            }
            """;

    @Test
    void explicitAndImplicitCatchTailBranchesAreEquivalent() {
        String explicit = """
                  if err is type ArithmeticOverflowError then
                    return 42;
                  else
                    return 1;
                  fi
                """;
        String implicit = """
                  if err is type ArithmeticOverflowError then
                    42;
                  else
                    1;
                  fi
                """;
        assertEquals(42L, evaluate(PREFIX + explicit + SUFFIX));
        assertEquals(42L, evaluate(PREFIX + implicit + SUFFIX));
    }

    @Test
    void commaSequenceProducesFinalValue() {
        assertEquals(47L, evaluate(PREFIX + """
                  if err is type ArithmeticOverflowError then
                    let int val = 5,
                    val + 42
                  else
                    1
                  fi
                """ + SUFFIX));
    }

    @Test
    void tailIfReturnsValue() {
        assertEquals(42L, evaluate("""
                pub fnc main(): int {
                  if true then
                    42
                  else
                    1
                  fi
                }
                """));
    }

    @Test
    void nonTailIfDoesNotReturnEarly() {
        assertEquals(99L, evaluate("""
                pub fnc main(): int {
                  if true then
                    42;
                  else
                    1;
                  fi
                  return 99;
                }
                """));
    }

    @Test
    void missingElseDoesNotProveTotalReturn() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main(): int {
                  if true then
                    42
                  fi
                }
                """)));
    }
}
