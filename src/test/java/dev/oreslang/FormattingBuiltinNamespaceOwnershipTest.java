package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormattingBuiltinNamespaceOwnershipTest {

    @Test
    void builtinProcessNamespaceHasNoGuestOwnerToPin() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc show(): void {
                  stdio.stdout.write(process.descriptor.scheduler_safepoints);
                  return;
                }
                """)));
    }

    @Test
    void otherUnknownRootsStayRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc show(): void {
                  stdio.stdout.write(processes.descriptor.scheduler_safepoints);
                  return;
                }
                """)));
    }

    @Test
    void legacyMutableBorrowSpellingStaysRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  let mut int value = 1;
                  val alias = rt borrow mut value;
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("rt mut borrow"), error.getMessage());
    }
}
