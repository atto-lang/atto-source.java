package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AffineSumProjectionExtractionTest {
    @Test
    void moveOnlyOptionProjectionCannotBeUnwrappedWithoutMoveTracking() {
        String source = """
                define class Box as
                  pub val int value = 42;
                end
                define class Holder as
                  pub let mut Option<Option<Box>> nested;
                end
                fnc bad(Holder holder): void {
                  val extracted = holder.nested.unwrap_all();
                  return;
                }
                """;
        assertRejectedAsProjection(source);
    }

    @Test
    void moveOnlyResultProjectionCannotBeExtractedWithoutMoveTracking() {
        String source = """
                define class Box as
                  pub val int value = 42;
                end
                define class Holder as
                  pub let mut Result<Box, String> nested;
                end
                fnc bad(Holder holder): void {
                  val extracted = holder.nested.unwrap();
                  return;
                }
                """;
        assertRejectedAsProjection(source);
    }

    @Test
    void copyableOptionProjectionRemainsValid() {
        String source = """
                define class Holder as
                  pub let mut Option<Option<int>> nested;
                end
                fnc good(Holder holder): int {
                  return holder.nested.unwrap_all();
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }

    private static void assertRejectedAsProjection(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException standalone = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(standalone.getMessage().contains("affine Option/Result projection"),
                standalone.getMessage());
        IllegalArgumentException integrated = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(integrated.getMessage().contains("affine Option/Result projection"),
                integrated.getMessage());
    }
}
