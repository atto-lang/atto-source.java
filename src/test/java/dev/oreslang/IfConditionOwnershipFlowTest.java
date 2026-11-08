package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A false if/else-if predicate has executed even though its body has not.
 * Moving an owner while evaluating that predicate must poison the false
 * successor, including the implicit empty-else edge.
 */
final class IfConditionOwnershipFlowTest {
    private static final String DEFINITIONS = """
            define class Resource as
              pub val int id = 7;
            end

            fnc consuming_predicate(Resource resource): bool {
              return resource.id eq 7;
            }

            fnc borrowing_predicate(borrow Resource resource): bool {
              return resource.id eq 7;
            }

            """;

    private static void rejectsMoved(String source) {
        assertDoesNotThrow(() -> Parser.parse(DEFINITIONS + source),
                "regression must reach ownership analysis, not fail parsing");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(DEFINITIONS + source));
        assertTrue(failure.getMessage().toLowerCase().contains("moved"),
                "expected a real ownership diagnostic: " + failure.getMessage());
    }

    @Test
    void consumingPredicatePoisonsItsElseSuccessor() {
        rejectsMoved("""
                fnc broken(Resource resource): int {
                  if consuming_predicate(resource) then
                    return 1;
                  else
                    return resource.id;
                  fi
                }
                """);
    }

    @Test
    void consumingPredicatePoisonsImplicitFalseSuccessor() {
        rejectsMoved("""
                fnc broken(Resource resource): int {
                  if consuming_predicate(resource) then
                    return 1;
                  fi
                  return resource.id;
                }
                """);
    }

    @Test
    void consumingPredicateCannotBeRecoveredByNestedElseIf() {
        rejectsMoved("""
                fnc broken(Resource resource, bool condition): int {
                  if consuming_predicate(resource) then
                    return 1;
                  else
                    if condition then
                      return resource.id;
                    else
                      return 0;
                    fi
                  fi
                }
                """);
    }

    @Test
    void immutableBorrowingPredicateDoesNotConsumeOwner() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DEFINITIONS + """
                fnc safe(Resource resource): int {
                  if borrowing_predicate(rt borrow resource) then
                    return resource.id;
                  fi
                  return resource.id;
                }
                """));
    }

    @Test
    void returningTrueArmMoveDoesNotPoisonIndependentFalseArm() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DEFINITIONS + """
                fnc safe(Resource resource, bool early): int {
                  if early then
                    let bool result = consuming_predicate(resource);
                    return 1;
                  fi
                  return resource.id;
                }
                """));
    }

    @Test
    void repeatingForPredicateMayNotConsumeAnOuterOwner() {
        String source = DEFINITIONS + """
                fnc broken(Resource resource): void {
                  for (const int counter = 0; consuming_predicate(resource); ) {
                    stdio.println("next iteration");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source),
                "loop regression must parse before ownership checking");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("repeating loop")
                        && failure.getMessage().contains("'resource'"),
                "loop predicate must not consume an outer owner twice: " + failure.getMessage());
    }

    @Test
    void forInitializerCanTransferOuterOwnerExactlyOnce() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(DEFINITIONS + """
                fnc safe(Resource resource): void {
                  for (val Resource local = resource; false; ) {
                    stdio.println("unreachable");
                  }
                  return;
                }
                """));
    }
}
