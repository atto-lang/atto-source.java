package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ReservedConcurrencyKeywordsTest {
    @Test void unimplementedContractsCannotBecomeOrdinaryFunctionsOrBindings() {
        for (String word : List.of("critical", "critical-read", "critical-write", "using", "with", "owns", "owner")) {
            for (String source : List.of(
                    "fnc " + word + "(): int { return 1; }",
                    "fnc test(): int { const " + word + ": int = 1; return 1; }",
                    "define class Box as pub val int " + word + " = 1; end",
                    "fnc test(int " + word + "): int { return 1; }")) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheck(source), source);
                assertTrue(failure.getMessage().contains("reserved keyword '" + word + "'"), failure::toString);
            }
        }
    }

    @Test void exactHyphenatedBoundariesPreserveOtherIdentifiersAndSubtraction() {
        assertEquals(List.of(Token.Type.CRITICAL_READ, Token.Type.CRITICAL_WRITE, Token.Type.EOF),
                new Lexer("critical-read critical-write").scan().stream().map(Token::type).toList());
        assertEquals(List.of(Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT, Token.Type.EOF),
                new Lexer("critical-reader").scan().stream().map(Token::type).toList());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc test(): int {
                  const owned: int = 10;
                  const ownership: int = 2;
                  const critical_value: int = owned - ownership;
                  const using_value: int = critical_value;
                  const withholding: int = using_value;
                  return withholding;
                }
                """));
    }
}
