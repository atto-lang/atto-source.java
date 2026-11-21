package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A guard can evaluate and return false after consuming an affine owner.
 * The next match arm is not allowed to resurrect that owner just because
 * the earlier arm's body did not execute.
 */
final class GuardedMatchOwnershipFlowTest {
    private static final String TYPES = """
            define class Resource as
              pub val int id = 7;
            end

            fnc consuming_predicate(take! Resource resource): bool {
              return resource.id eq 7;
            }

            fnc borrowed_predicate(borrow Resource resource): bool {
              return resource.id eq 7;
            }

            """;

    private static void rejectsMoved(String body) {
        String source = TYPES + body;
        assertDoesNotThrow(() -> Parser.parse(source),
                "fixture must reach semantic ownership checker, not fail syntax");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().toLowerCase().contains("moved"),
                "expected a semantic moved-owner diagnostic: " + error.getMessage());
    }

    @Test
    void falseGuardMoveMustPoisonFallbackArm() {
        rejectsMoved("""
                fnc bad(Resource resource, int number): int {
                  match number over
                    on int x when consuming_predicate(resource) -> {
                      return 1;
                    }
                    on _ -> {
                      return resource.id;
                    }
                  end
                }
                """);
    }

    @Test
    void falseGuardMoveMustPoisonNextGuard() {
        rejectsMoved("""
                fnc bad(Resource resource, int number): int {
                  match number over
                    on int x when consuming_predicate(resource) -> {
                      return 1;
                    }
                    on int y when borrowed_predicate(rt borrow resource) -> {
                      return 2;
                    }
                    on _ -> {
                      return 0;
                    }
                  end
                }
                """);
    }

    @Test
    void valueMatchFalseGuardMoveCannotLaunderOwner() {
        rejectsMoved("""
                fnc bad(Resource resource, int number): int {
                  return match number over {
                    on int x when consuming_predicate(resource) { return 1; }
                    on _ { return resource.id; }
                  };
                }
                """);
    }

    @Test
    void immutableGuardBorrowKeepsFallbackOwnerUsable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TYPES + """
                fnc good(Resource resource, int number): int {
                  match number over
                    on int x when borrowed_predicate(rt borrow resource) -> {
                      return 1;
                    }
                    on _ -> {
                      return resource.id;
                    }
                  end
                }
                """));
    }

    @Test
    void moveInReturningArmBodyDoesNotAffectOtherPattern() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TYPES + """
                fnc good(Resource resource, int number): int {
                  match number over
                    on 0 -> {
                      let Resource moved = rt take resource;
                      return moved.id;
                    }
                    on _ -> {
                      return resource.id;
                    }
                  end
                }
                """));
    }
    @Test
    void falseGuardMovePoisonsPostMatchFallthrough() {
        rejectsMoved("""
                fnc bad(Resource resource, int number): int {
                  match number over
                    on int x when consuming_predicate(resource) -> {
                      stdio.stdout.write(1);
                    }
                    on _ -> {
                      stdio.stdout.write(2);
                    }
                  end
                  return resource.id;
                }
                """);
    }

    @Test
    void borrowedValueMatchGuardKeepsFallbackOwnerUsable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TYPES + """
                fnc good(Resource resource, int number): int {
                  return match number over {
                    on int x when borrowed_predicate(rt borrow resource) { return 1; }
                    on _ { return resource.id; }
                  };
                }
                """));
    }

}
