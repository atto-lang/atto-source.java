package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every condition must be a Boolean predicate, not a disguised assignment.
 * An assignment outside a predicate is a separate compatibility decision.
 */
final class ConditionalAssignmentHardeningTest {
    @Test
    void directAndParenthesizedAssignmentsNeverBecomeIfConditions() {
        for (String condition : new String[] {
                "x = y",
                "(x = y)",
                "((x = y))",
                "(x = y) eq true",
                "And(true, (x = y))",
                "(x eq y) && (x = y)"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(ifSource(condition))), condition);
            assertTrue(error.getMessage().contains("assignment")
                    || error.getMessage().contains("expected 'then'"), condition + ": " + error.getMessage());
        }
    }

    @Test
    void elseifConditionAndMatchGuardRejectAssignment() {
        IllegalArgumentException elseif = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc probe(bool x, bool y): int {
                          if false then
                            return 0;
                          elif (x = y) then
                            return 1;
                          else
                            return 2;
                          fi
                        }
                        """)));
        assertTrue(elseif.getMessage().contains("assignment"));

        IllegalArgumentException guard = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc probe(bool x, bool y): int {
                          match x over
                            on true when (x = y) -> { return 1; }
                            on _ -> { return 0; }
                          end
                        }
                        """)));
        assertTrue(guard.getMessage().contains("assignment"));
    }

    @Test
    void ternaryPredicateRejectsAssignmentEvenThoughOtherExpressionsAllowIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc probe(bool x, bool y): int {
                          return (x = y) ? 1 : 0;
                        }
                        """)));
        assertTrue(error.getMessage().contains("assignment"));
    }

    @Test
    void validBooleanConditionsAndCanonicalEqualityStillWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(ifSource("x eq y"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(ifSource("x == y"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(ifSource("x && y"))));
    }

    @Test
    void optionsDoNotBecomeImplicitBooleans() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc probe(Option<int> value): int {
                          if value then
                            return 1;
                          else
                            return 0;
                          fi
                        }
                        """)));
    }

    private static String ifSource(String condition) {
        return """
                fnc probe(bool x, bool y): bool {
                  if """ + condition + """
                   then
                    return true;
                  else
                    return false;
                  fi
                }
                """;
    }
}
