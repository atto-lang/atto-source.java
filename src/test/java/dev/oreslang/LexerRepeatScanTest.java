package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Host lexer re-entry regression; not a guest execution or AOT claim. */
final class LexerRepeatScanTest {
    @Test
    void rescanningReturnsAnIdenticalFreshSnapshotWithOneEof() {
        Lexer lexer = new Lexer("let value = 12;\r\n"
                + "/* outer /* inner */ outer */\n"
                + "#ready");
        List<Token> first = lexer.scan();
        List<Token> second = lexer.scan();

        assertEquals(first, second);
        assertNotSame(first, second);
        assertEquals(1L, first.stream()
                .filter(token -> token.type() == Token.Type.EOF).count());
        assertEquals(new Token(Token.Type.SYMBOL_LITERAL, "ready", 3, 1),
                second.get(second.size() - 2));
        assertEquals(new Token(Token.Type.EOF, "", 3, 7), second.getLast());
    }

    @Test
    void rejectedPartialScanDoesNotLeaveStaleCursorOrTokens() {
        Lexer lexer = new Lexer("let value = 12;\r\n/* unclosed");
        String first = assertThrows(IllegalArgumentException.class,
                lexer::scan).getMessage();
        String second = assertThrows(IllegalArgumentException.class,
                lexer::scan).getMessage();

        assertEquals(first, second);
    }

    @Test
    void emptySourceYieldsExactlyOneEofOnEveryScan() {
        Lexer lexer = new Lexer("");
        List<Token> expected = List.of(new Token(Token.Type.EOF, "", 1, 1));
        assertEquals(expected, lexer.scan());
        assertEquals(expected, lexer.scan());
    }
}
