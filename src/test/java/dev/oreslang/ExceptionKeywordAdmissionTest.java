package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ExceptionKeywordAdmissionTest {
    @Test
    void keywordsRemainReservedUntilTypedExceptionalControlIsLowered() {
        String[] spellings = {"throw", "raise", "panic", "recover"};
        Token.Type[] expected = {
                Token.Type.THROW, Token.Type.RAISE, Token.Type.PANIC, Token.Type.RECOVER
        };
        for (int i = 0; i < spellings.length; i++) {
            assertEquals(expected[i], new Lexer(spellings[i]).scan().getFirst().type(),
                    spellings[i] + " must not be silently treated as a local identifier");
        }
    }

    @Test
    void unsupportedEffectStatementsFailClosedRatherThanCompileAsCalls() {
        for (String keyword : new String[]{"throw", "raise", "panic", "recover"}) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("pub fnc main(): void { " + keyword
                            + " 7; return; }"), keyword);
            assertTrue(error.getMessage().contains("typed exception effects"),
                    error.getMessage());
        }
    }
}
