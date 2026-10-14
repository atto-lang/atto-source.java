package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Explicit procedural dispatch must not break existing statement programs.
 * Value-producing static select expressions must remain distinct from statements.
 */
final class ExplicitDispatchStatementSyntaxTest {
    @Test
    void legacyMatchSwitchAndStaticSelectRemainParseable() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc check_match(int x): int {
                    match x over
                        on 1 -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc check_switch(int x): int {
                    switch x
                        case 1 -> { return 1; }
                        default -> { return 2; }
                    end
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc check_select(): void {
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

    @Test
    void typedStaticValueExpressionIsNowSupported() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc bad(): int {
                    const int value = select { default: { return 1; } };
                    return value;
                }
                """));
    }
}
