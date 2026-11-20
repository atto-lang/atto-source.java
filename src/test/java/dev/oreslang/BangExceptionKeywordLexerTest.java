package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class BangExceptionKeywordLexerTest {
    @Test
    void contiguousExceptionKeywordsAreAtomicWithoutChangingTheirMeaning() {
        List<Token> tokens = new Lexer("trap! call; throw! err; raise! err; panic! err").scan();
        assertEquals(List.of(Token.Type.TRAP, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.THROW, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.RAISE, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.PANIC, Token.Type.IDENT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals(List.of("trap!", "throw!", "raise!", "panic!"),
                tokens.stream().filter(t -> t.type() == Token.Type.TRAP ||
                        t.type() == Token.Type.THROW || t.type() == Token.Type.RAISE ||
                        t.type() == Token.Type.PANIC).map(Token::lexeme).toList());
    }

    @Test
    void whitespaceInequalityCommentsAndIdentifiersStaySeparate() {
        List<Token> tokens = new Lexer("trap !x; trap!eq throw!=; trapped! // panic!").scan();
        assertEquals(List.of(Token.Type.TRAP, Token.Type.BANG, Token.Type.IDENT,
                Token.Type.SEMICOLON, Token.Type.TRAP, Token.Type.NEQ,
                Token.Type.THROW, Token.Type.BANG_EQUAL, Token.Type.SEMICOLON,
                Token.Type.IDENT, Token.Type.BANG, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
    }
}
