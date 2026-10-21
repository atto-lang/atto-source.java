package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical mutable-loan operation is 'rt mut borrow', never the
 * historical reversed operation order. Structural alias regressions
 * depend on this grammar entry point actually parsing.
 */
final class CanonicalMutableBorrowParserTest {
    @Test
    void acceptsCanonicalMutableBorrowInCallArguments() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Widget as
                  pub let int id = 1;
                end
                fnc write(borrow mut Widget item): int { return item.id; }
                fnc check(): int {
                  let Widget owner = new Widget();
                  return write(rt mut borrow owner);
                }
                """));
    }

    @Test
    void rejectsReversedMutableBorrowAsInvalidSyntax() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Widget as
                  pub let int id = 1;
                end
                fnc write(borrow mut Widget item): int { return item.id; }
                fnc bad(): int {
                  let Widget owner = new Widget();
                  return write(rt borrow mut owner);
                }
                """));
    }

    @Test
    void mutableRuntimeQualifierCannotApplyToUnrelatedOperations() {
        for (String illegal : new String[] {
                "rt mut take owner",
                "rt mut copy owner",
                "rt mut share owner",
                "rt mut cooperate"
        }) {
            String source = "fnc bad(): void { " + illegal + "; }";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
        }
    }
}
