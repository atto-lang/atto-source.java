package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-language ownership admission only. Runtime backing and real guest
 * execution are independently gated by native-tensor-span.yml. This test does
 * not prove actor heap confinement or physical memory retirement.
 */
final class TensorOwnedArrayAdmissionTest {
    private static final String OWNER = """
            define class TensorOwner as
              pub let mut Array<int> values = new Array<int>();
            end
            """;

    @Test
    void classOwnedNativeArraysTypecheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(OWNER + """
                pub routine main(): void {
                  let mut TensorOwner first = new TensorOwner();
                  let mut TensorOwner other = new TensorOwner();
                  first.values.add(19);
                  other.values.add(23);
                  let mut TensorOwner moved = rt take(first);
                  moved.values.set(0, 20);
                  stdio.stdout.write(moved.values.get(0) + other.values.get(0));
                  return;
                }
                """)));
    }

    @Test
    void movedTensorOwnerCannotBeReused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(OWNER + """
                    pub routine main(): void {
                      let mut TensorOwner original = new TensorOwner();
                      original.values.add(42);
                      let mut TensorOwner moved = rt take(original);
                      stdio.stdout.write(original.values.get(0));
                      return;
                    }
                    """)));
        assertTrue(error.getMessage().toLowerCase().contains("mov")
                        || error.getMessage().toLowerCase().contains("tak"),
                error.getMessage());
    }

    @Test
    void borrowedOwnerCannotBeMovedWhileReadLoanIsLive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(OWNER + """
                    pub routine main(): void {
                      let mut TensorOwner original = new TensorOwner();
                      original.values.add(42);
                      val borrow TensorOwner reader = rt borrow original;
                      let mut TensorOwner moved = rt take(original);
                      stdio.stdout.write(reader.values.get(0));
                      return;
                    }
                    """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow")
                        || error.getMessage().toLowerCase().contains("loan"),
                error.getMessage());
    }
}
