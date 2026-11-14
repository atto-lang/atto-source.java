package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical conditional arm delimiters are mutually exclusive. */
final class ConditionalDelimiterSyntaxTest {
    private static String withCatch(String branch) {
        return """
                pub fnc checked_add(int lhs, int rhs): int {
                  return lhs + rhs;
                }
                pub fnc main(): int {
                  try {
                    return checked_add(9223372036854775807, 1);
                  } catch (err) {
                """ + branch + """
                  } finally {
                  }
                }
                """;
    }

    @Test
    void bracedGuestErrorConditionalNeedsNoFi() {
        String branch = """
                    if err is type ArithmeticOverflowError {
                      return 42;
                    } else {
                      return 1;
                    }
                """;
        assertDoesNotThrow(() -> Parser.parse(withCatch(branch)));
    }

    @Test
    void keywordGuestErrorConditionalClosesWithFi() {
        String branch = """
                    if err is type ArithmeticOverflowError then
                      return 42;
                    else
                      return 1;
                    fi
                """;
        assertDoesNotThrow(() -> Parser.parse(withCatch(branch)));
    }

    @Test
    void nestedBracedIfDoesNotConsumeOuterKeywordFi() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): int {
                  if true then
                    if true { return 42; } else { return 1; }
                  else
                    return 0;
                  fi
                }
                """));
    }

    @Test
    void extraFiAfterBracedIfIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): int {
                  if true { return 42; } else { return 1; } fi
                }
                """));
    }
}
