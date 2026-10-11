package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TypedMatchFlowEscapeTest {
    private static void rejectsArmEscape(String armBody) {
        String source = """
                fnc reject(int n): bool {
                  loop {
                    do match<ret=bool> n over {
                      on 1 {
                        %s
                      }
                      default { return false; }
                    }
                  }
                }
                """.formatted(armBody);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        assertTrue(invalid.getMessage().contains("must return"),
                "Expected typed-return contract rejection: " + invalid.getMessage());
    }

    @Test
    void breakBeforeReturnDoesNotProveTypedArmReturn() {
        rejectsArmEscape("break; return true;");
        rejectsArmEscape("{ break; } return true;");
    }

    @Test
    void continueBeforeReturnDoesNotProveTypedArmReturn() {
        rejectsArmEscape("continue; return true;");
        rejectsArmEscape("{ continue; } return true;");
    }

    @Test
    void conditionalBreakCannotBeHiddenByLaterReturn() {
        rejectsArmEscape("""
                if n == 1 { break; } fi
                return true;
                """);
        rejectsArmEscape("""
                if n == 1 { return true; } else { continue; } fi
                return false;
                """);
    }

    @Test
    void switchEscapeRemainsVisibleThroughLaterReturn() {
        rejectsArmEscape("""
                switch n {
                  case 1 { break; }
                  default { return true; }
                }
                return false;
                """);
    }

    @Test
    void completeConditionalReturnsStillSatisfyTypedContract() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                fnc classify(int n): bool {
                  do match<ret=bool> n over {
                    on 1 {
                      if n == 1 { return true; }
                      else { return false; } fi
                    }
                    default { return false; }
                  }
                }
                """)));
    }
}
