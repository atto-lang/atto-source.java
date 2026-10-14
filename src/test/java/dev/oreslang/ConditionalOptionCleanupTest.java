package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Condition-bound locals must not inherit the cleanup identity of outer names. */
final class ConditionalOptionCleanupTest {
    @Test
    void returnedCopyBindingShadowsUnrelatedDeferredReassignment() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose(Option<int> maybe): int {
                  let int value = 4;
                  defer value = 7;
                  if maybe as const value then
                    return value;
                  else
                    return 0;
                  fi
                }
                """)));
    }

    @Test
    void returnedOuterBindingStillRejectsDeferredReassignment() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc choose(Option<int> maybe): int {
                          let int value = 4;
                          defer value = 7;
                          if maybe as const candidate then
                            return value;
                          else
                            return 0;
                          fi
                        }
                        """)));
        assertTrue(error.getMessage().contains("defer cannot reassign returned binding 'value'"),
                error.getMessage());
    }
}
