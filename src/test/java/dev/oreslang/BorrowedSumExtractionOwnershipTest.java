package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A shared borrow never grants authority to move an affine sum payload. */
final class BorrowedSumExtractionOwnershipTest {
    private static final String BOX = """
            define class Box as
              pub val int value = 42;
            end
            """;

    @Test
    void nestedOptionCannotMoveItsBoxThroughBorrow() {
        reject(BOX + """
                fnc bad(borrow Option<Option<Box>> owner): Box {
                  return owner.unwrap_all();
                }
                """, "affine Option/Result through a borrow");
    }

    @Test
    void flattenCannotReturnAffineInnerOptionThroughBorrow() {
        reject(BOX + """
                fnc bad(borrow Option<Option<Box>> owner): Option<Box> {
                  return owner.flatten();
                }
                """, "affine Option/Result through a borrow");
    }

    @Test
    void resultCannotExtractOwnedBoxThroughBorrow() {
        reject(BOX + """
                fnc bad(borrow Result<Box, String> owner): Box {
                  return owner.unwrap();
                }
                """, "affine Option/Result through a borrow");
    }

    @Test
    void unwrapSafeCannotPackageAffineBorrowedValue() {
        reject(BOX + """
                fnc bad(borrow Option<Box> owner): Result<Box, OptionUnwrapError> {
                  return owner.unwrap_safe();
                }
                """, "affine Option/Result through a borrow");
    }

    @Test
    void unwrapOrCannotEscapeBorrowedAffinePayload() {
        reject(BOX + """
                fnc bad(borrow Option<Box> owner): Box {
                  return owner.unwrap_or(new Box());
                }
                """, "affine Option/Result through a borrow");
    }

    @Test
    void borrowedResultCanPerformReadOnlyQuery() {
        accept(BOX + """
                fnc query(borrow Result<Box, String> owner): bool {
                  return owner.is_ok();
                }
                """);
    }

    @Test
    void borrowedOptionCanStillPerformReadOnlyQuery() {
        accept(BOX + """
                fnc query(borrow Option<Box> owner): bool {
                  return owner.is_some();
                }
                """);
    }

    @Test
    void borrowedCopyableNestedOptionCanStillExtract() {
        accept("""
                fnc copy(borrow Option<Option<int>> owner): int {
                  return owner.unwrap_all();
                }
                """);
    }

    private static void reject(String source, String diagnostic) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException standalone = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
        assertTrue(standalone.getMessage().contains(diagnostic),
                standalone.getMessage());
        IllegalArgumentException integrated = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(integrated.getMessage().contains(diagnostic),
                integrated.getMessage());
    }

    private static void accept(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }
}
