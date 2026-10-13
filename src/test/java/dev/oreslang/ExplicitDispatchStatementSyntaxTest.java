package dev.oreslang;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExplicitDispatchStatementSyntaxTest {
    @Test void bareStatementsAreNotValidDispatchForms() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc x(int n): int {
                match n over
                    on 1 -> { return 1; }
                    on _ -> { return 2; }
                end
            }
            """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc x(int n): int {
                switch n
                    case 1 -> { return 1; }
                    default -> { return 2; }
                end
            }
            """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc x(): void {
                select { default: { return; } }
            }
            """));
    }

    @Test void proceduralFormsRequireDo() {
        assertDoesNotThrow(() -> Parser.parse("""
            fnc x(int n): int {
                do match n over
                    on 1 -> { return 1; }
                    on _ -> { return 2; }
                end
            }
            fnc y(int n): int {
                do switch n
                    case 1 -> { return 1; }
                    default -> { return 2; }
                end
            }
            fnc z(): void {
                do select { default: { return; } }
            }
            """));
    }
}
