package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BorrowFormalBoundaryRegressionTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void directYieldCannotCreateAndExportBorrowAfterPreYieldCheck() {
        reject(BOX + """
                gen* fnc bad(): Iterator<borrow Box> {
                  val Box box = new Box();
                  yield rt borrow box;
                  return;
                }
                """, "cannot yield a borrowed value");
    }

    @Test
    void delegatedYieldCannotHideBorrowInNestedElement() {
        reject(BOX + """
                gen* fnc bad(Iterator<Option<borrow Box>> source): Iterator<Option<borrow Box>> {
                  yield* source;
                  return;
                }
                """, "cannot yield borrowed elements");
    }

    @Test
    void constructorCannotRetainTemporaryBorrowWithoutLifetimeContract() {
        reject(BOX + """
                fnc bad(): void {
                  val Box box = new Box();
                  val result = new Box(rt borrow box);
                  return;
                }
                """, "constructor cannot retain a borrowed argument");
    }

    @Test
    void ownedScalarGeneratorStillAdmitted() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                gen* fnc good(): Iterator<int> {
                  val int value = 7;
                  yield value;
                  return;
                }
                """)));
    }
}
