package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiler ownership gate only; does not prove guest execution or a shared
 * heap/runtime lowering. A mutable owned array is not an implicit Arc/Shared.
 */
final class ShareMoveOnlyDiagnosticTest {
    @Test
    void rtShareOfOwnedMutableArrayHasStableSemanticDiagnostic() {
        String source = """
                pub fnc work(): void {
                  let mut Array<int> entries = new Array<int>();
                  let alias = rt share entries;
                  return;
                }
                """;
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("E_SHARE_MOVE_ONLY"),
                denied::toString);
        assertTrue(denied.getMessage().contains("move-only"),
                denied::toString);
    }

    @Test
    void rtShareDoesNotRejectAnImmutableCopyableScalar() {
        String source = """
                pub fnc work(): void {
                  let int value = 42;
                  let int alias = rt share value;
                  return;
                }
                """;
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void inlineConstructedMutableValueCannotBypassShareAdmission() {
        String source = """
                pub fnc work(): void {
                  let alias = rt share new Array<int>();
                  return;
                }
                """;
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("E_SHARE_MOVE_ONLY"),
                denied::toString);
    }
}
