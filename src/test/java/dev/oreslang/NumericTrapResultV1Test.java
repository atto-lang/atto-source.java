package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate guest-executed regressions for numerical ordinary Error -> Result
 * trap semantics. These do NOT certify execution until the exact head runs.
 */
final class NumericTrapResultV1Test {
    private static long run(String source) {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(OresLanguage.ID, source).asLong();
        }
    }

    @Test
    void expressionTrapIsTypedResultNotOption() {
        String guest = """
                pub fnc checked(int increment): int {
                  return 9223372036854775807 + increment;
                }
                pub fnc main(): int {
                  val result = trap checked(1);
                  return result.is_err() ? 42 : 1;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(guest)));
        assertEquals(42L, run(guest));
    }

    @Test
    void trapCatchesNumericDivisionByZeroAsErrNotNone() {
        String guest = """
                pub fnc checked(int divisor): int { return 7 / divisor; }
                pub fnc main(): int {
                  val result = trap checked(0);
                  return result.is_err() ? 42 : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }

    @Test
    void trapSuccessIsOkWithTheOriginalScalarValue() {
        String guest = """
                pub fnc checked(int divisor): int { return 84 / divisor; }
                pub fnc main(): int {
                  val result = trap checked(2);
                  return result.is_ok() ? result.unwrap() : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }

    @Test
    void resultOperandIsNotNestedIntoResultOfResult() {
        String guest = """
                pub fnc main(): int {
                  val result = trap Ok(42);
                  return result.is_ok() ? result.unwrap() : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }

    @Test
    void preexistingErrRemainsErrWithoutBeingRewrapped() {
        String guest = """
                pub fnc main(): int {
                  val result = trap Err("existing");
                  return result.is_err() ? 42 : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }

    @Test
    void trapCallableReturnsResultOnRuntimeIntegerOverflow() {
        String guest = """
                pub trap fnc checked(int x): int {
                  return 9223372036854775807 + x;
                }
                pub fnc main(): int {
                  val result = checked(1);
                  return result.is_err() ? 42 : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }

    @Test
    void trapCallableReturnsOkOnNormalCompletion() {
        String guest = """
                pub trap fnc checked(int value): int {
                  return value + 2;
                }
                pub fnc main(): int {
                  val result = checked(40);
                  return result.is_ok() ? result.unwrap() : 1;
                }
                """;
        assertEquals(42L, run(guest));
    }
}
