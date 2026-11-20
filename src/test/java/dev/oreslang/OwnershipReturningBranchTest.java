package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A move inside a branch that always returns cannot reach code after the
 * branch, so it must not make later uses "use of moved value". Branches that
 * fall through (or only break/continue) still count.
 */
final class OwnershipReturningBranchTest {
    private static final String RESOURCE = """
            define class Resource as
              pub val int id = 7;
            end

            fnc consume(Resource value): int { return value.id; }
            """;

    private static void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(RESOURCE + body)));
    }

    private static void rejectsMoved(String body, String name) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(RESOURCE + body)));
        assertTrue(failure.getMessage().contains("use of moved value '" + name + "'"),
                failure.getMessage());
    }

    @Test
    void moveInReturningIfBranchDoesNotPoisonLaterUse() {
        accepts("""
                fnc pick(Resource resource, bool early): int {
                  if early then
                    return consume(resource);
                  fi
                  return resource.id;
                }
                """);
    }

    @Test
    void moveInReturningElseBranchDoesNotPoisonLaterUse() {
        accepts("""
                fnc pick(Resource resource, bool keep): int {
                  if keep then
                    stdio.println("keep");
                  else
                    return consume(resource);
                  fi
                  return resource.id;
                }
                """);
    }

    @Test
    void moveInNestedFullyReturningIfStillCountsAsReturning() {
        accepts("""
                fnc pick(Resource resource, bool early, bool odd): int {
                  if early then
                    if odd then
                      return consume(resource);
                    else
                      return 0;
                    fi
                  fi
                  return resource.id;
                }
                """);
    }

    @Test
    void moveInFallThroughBranchStillPoisonsLaterUse() {
        rejectsMoved("""
                fnc pick(Resource resource, bool early): int {
                  if early then
                    let int ignored = consume(resource);
                  fi
                  return resource.id;
                }
                """, "resource");
    }

    @Test
    void partiallyReturningNestedIfStillPoisonsLaterUse() {
        rejectsMoved("""
                fnc pick(Resource resource, bool early, bool odd): int {
                  if early then
                    let int ignored = consume(resource);
                    if odd then
                      return 1;
                    fi
                  fi
                  return resource.id;
                }
                """, "resource");
    }

    @Test
    void moveBeforeBreakIsStillRejected() {
        // break reaches code after the loop; the loop rule rejects the move first.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(RESOURCE + """
                fnc pick(Resource resource): int {
                  let int count = 0;
                  loop do
                    let int ignored = consume(resource);
                    break;
                  done
                  return resource.id;
                }
                """)));
        assertTrue(failure.getMessage().contains("'resource'"), failure.getMessage());
    }

    @Test
    void moveInsideReturningBranchIsStillCheckedWithinThatBranch() {
        rejectsMoved("""
                fnc pick(Resource resource, bool early): int {
                  if early then
                    let int first = consume(resource);
                    return consume(resource);
                  fi
                  return resource.id;
                }
                """, "resource");
    }
}
