package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A failed Option guard is not a rollback of ownership effects in its producer.
 * These cases deliberately exercise the paths after fi, not just the else body.
 */
final class ConditionalGuardFailureEdgeOwnershipTest {
    private static final String PRELUDE = """
            define class Box as
            end

            fnc consume(Box value): Option<int> {
              return Some(7);
            }
            """;

    private static void rejectsMoved(String body) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PRELUDE + body)));
        assertTrue(failure.getMessage().contains("moved")
                        || failure.getMessage().contains("move"),
                failure.getMessage());
    }

    @Test
    void fallingThroughIfWithoutElseDoesNotReviveConsumedProducerArgument() {
        rejectsMoved("""
                fnc bad(Box box): void {
                  if consume(box) as const value then
                    return;
                  fi
                  consume(box);
                  return;
                }
                """);
    }

    @Test
    void failingConjunctCannotReviveProducerArgumentAfterFi() {
        rejectsMoved("""
                fnc bad(Box box): void {
                  if consume(box) as let value && false then
                    return;
                  fi
                  consume(box);
                  return;
                }
                """);
    }

    @Test
    void laterElifProducerIsAlsoConsumedOnItsFailureEdge() {
        rejectsMoved("""
                fnc bad(Box first, Box second): void {
                  if consume(first) as const left then
                    return;
                  elif consume(second) as const right then
                    return;
                  fi
                  consume(second);
                  return;
                }
                """);
    }

    @Test
    void unrelatedOwnersRemainAvailableAfterFailedGuard() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PRELUDE + """
                fnc good(Box first, Box second): void {
                  if consume(first) as const value then
                    return;
                  fi
                  consume(second);
                  return;
                }
                """)));
    }
}
