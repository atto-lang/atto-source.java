package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural loans cannot silently lose source ownership when an argument
 * is an expression rather than a simple named place.
 */
final class StructuralBorrowProvenanceTest {
    @Test
    void callableProducedBorrowCannotBeUsedWithoutProvenLifetime() {
        String source = """
                define class Point as
                  pub val int id = 1;
                end
                fnc identity(borrow Point input): borrow Point { return input; }
                fnc read(struct{id: int} view, Point consumed): int {
                  return view.id + consumed.id;
                }
                fnc bad(): int {
                  val Point p = new Point();
                  return read(identity(rt borrow p), rt take p);
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("borrow"),
                error.getMessage());
    }

    @Test
    void lexicalStructuralBorrowProtectsOwnerDuringOtherArguments() {
        String source = """
                define class Point as
                  pub val int id = 1;
                end
                fnc read(struct{id: int} view, Point consumed): int {
                  return view.id + consumed.id;
                }
                fnc bad(): int {
                  val Point p = new Point();
                  return read(p, rt take p);
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("borrow"),
                error.getMessage());
    }
}
