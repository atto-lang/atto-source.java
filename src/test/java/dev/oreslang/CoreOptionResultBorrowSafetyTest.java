package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core Option/Result must use the existing ownership checker, not an
 * unchecked library-level conversion from a borrowed affine sum to an owner.
 *
 * These are compiler/type-checker requirements, NOT successful execution
 * receipts. Keep this class red if the integrated checker fails open.
 */
final class CoreOptionResultBorrowSafetyTest {
    private static final String BOX = """
            define class Box as
              pub val int value = 42;
            end
            """;

    private static void accept(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed),
                "standalone borrow analysis must admit a read-only query");
        assertDoesNotThrow(() -> TypeChecker.check(parsed),
                "integrated typechecker must admit a read-only query");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                "compiler entrypoint must use the same borrow rules");
    }

    private static void rejectAffineBorrowEscape(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                "test must exercise semantic borrow checks, not syntax rejection");
        IllegalArgumentException standalone = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed),
                "borrowed affine payload must not become an owned value");
        assertTrue(standalone.getMessage().contains("affine Option/Result through a borrow"),
                standalone::getMessage);
        IllegalArgumentException integrated = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(parsed),
                "compiler-linked borrow analysis must reject the same escape");
        assertTrue(integrated.getMessage().contains("affine Option/Result through a borrow"),
                integrated::getMessage);
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(source),
                "compiler entrypoint must not emit a guest from invalid ownership");
    }

    @Test
    void readOnlyQueriesPreserveBorrowerAndCarrier() {
        accept(BOX + """
                fnc query(borrow Option<Box> value): bool {
                  return value.is_some();
                }
                """);
        accept(BOX + """
                fnc query(borrow Result<Box, String> value): bool {
                  return value.is_ok();
                }
                """);
        accept(BOX + """
                fnc query(borrow Option<Option<Box>> value): bool {
                  return value.is_none();
                }
                """);
    }

    @Test
    void borrowedNestedOptionCannotFlattenIntoAnOwner() {
        rejectAffineBorrowEscape(BOX + """
                fnc invalid(borrow Option<Option<Box>> value): Option<Box> {
                  return value.flatten();
                }
                """);
    }

    @Test
    void borrowedOptionCannotExtractOwnedAffineValue() {
        rejectAffineBorrowEscape(BOX + """
                fnc invalid(borrow Option<Option<Box>> value): Box {
                  return value.unwrap_all();
                }
                """);
    }

    @Test
    void borrowedResultCannotExtractOwnedAffineValue() {
        rejectAffineBorrowEscape(BOX + """
                fnc invalid(borrow Result<Box, String> value): Box {
                  return value.unwrap();
                }
                """);
    }

    @Test
    void borrowedOptionCannotRepackageAffineOwnedValue() {
        rejectAffineBorrowEscape(BOX + """
                fnc invalid(borrow Option<Box> value): Result<Box, OptionUnwrapError> {
                  return value.unwrap_safe();
                }
                """);
    }

    @Test
    void borrowedOptionCannotEscapeThroughEagerFallback() {
        rejectAffineBorrowEscape(BOX + """
                fnc invalid(borrow Option<Box> value): Box {
                  return value.unwrap_or(new Box());
                }
                """);
    }

    @Test
    void copyableNestedOptionCanStillReadWithoutStealingOwnership() {
        accept("""
                fnc read(borrow Option<Option<int>> value): int {
                  return value.unwrap_all();
                }
                """);
    }
}
