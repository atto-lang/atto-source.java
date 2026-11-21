package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anonymous structural views are non-owning sync-only capabilities.
 * Optional syntax and parameter order must never change their admission.
 */
final class AnonymousStructuralBoundaryTest {
    private static final String[] SHAPES = {
            "struct{id: int} row",
            "row: struct{id: int}",
            "structural struct{id: int} row",
            "row: structural struct{id: int}",
            "record{id: int} row",
            "row: structural record{id: int}"
    };

    @Test
    void allAnonymousSpellingsRejectAsyncEscapes() {
        for (String shape : SHAPES) {
            String source = "async fnc invalid(" + shape
                    + "): int { return row.id; }";
            var parsed = assertDoesNotThrow(() -> Parser.parse(source), source);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(parsed), source);
        }
    }

    @Test
    void allAnonymousSpellingsRejectMailboxBoundaries() {
        for (String shape : SHAPES) {
            String source = "define module app\n actor fnc invalid(" + shape
                    + "): void { return; }\nend";
            var parsed = assertDoesNotThrow(() -> Parser.parse(source), source);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(parsed), source);
        }
    }

    @Test
    void returningBorrowedStructuralViewCannotManufactureOwnedStruct() {
        for (String shape : SHAPES) {
            String source = "fnc invalid(" + shape
                    + "): struct{id: int} { return row; }";
            var parsed = assertDoesNotThrow(() -> Parser.parse(source), source);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(parsed), source);
        }
    }

    @Test
    void nestedWrapperDoesNotImplicitlyEnableStructuralLoan() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc nominal(Option<struct{id: int}> rows): void { return; }
                fnc explicit(structural struct{id: int} row): void { return; }
                """));
    }
}
