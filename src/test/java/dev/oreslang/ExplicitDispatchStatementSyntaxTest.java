package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical procedural dispatch does not yet remove established legacy forms. */
final class ExplicitDispatchStatementSyntaxTest {
    @Test
    void legacyStatementFormsRemainAcceptedWhileValueMatchesArePending() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc bad(int x): int {
                    match x over
                        on 1 -> { return 1; }
                        on _ -> { return 2; }
                    end
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc bad(int x): int {
                    switch x
                        case 1 -> { return 1; }
                        default -> { return 2; }
                    end
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc bad(): void {
                    select { default: { return; } }
                }
                """));
    }

    @Test
    void returnMatchIsAnExpressionWithItsOwnResult() {
        // A value match is a separate AST and return-scope operation, not a
        // procedural match or implicit actor response.
        assertDoesNotThrow(() -> dev.oreslang.types.TypeChecker.check(Parser.parse("""
                fnc compute(int x): int {
                    return match x over {
                        on _ { return x; }
                    };
                }
                """)));
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
