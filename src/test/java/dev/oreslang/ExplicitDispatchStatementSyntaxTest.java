package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical dispatch mode is always explicit at the statement boundary. */
final class ExplicitDispatchStatementSyntaxTest {
    @Test
    void bareMatchSwitchAndStaticSelectAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int x): int {
                    match x over
                        on 1 -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int x): int {
                    switch x
                        case 1 -> { return 1; }
                        default -> { return 2; }
                    end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                    select { default: { return; } }
                }
                """));
    }

    @Test
    void proceduralDoFormsAreAdmittedByParser() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc process(int x): int {
                    do match x over
                        on 1 -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                fnc branch(int x): int {
                    do switch x
                        case 1 -> { return 1; }
                        default -> { return 2; }
                    end
                }
                fnc receive(): void {
                    do select { default: { return; } }
                }
                """));
    }
}
