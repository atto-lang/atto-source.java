package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HyphenatedCompoundKeywordTest {
    @Test
    void nbSelectLexesLikeNbSelectWithSourceColumns() {
        List<Token> tokens = new Lexer("nb-select").scan();
        assertEquals(List.of(Token.Type.NB, Token.Type.SELECT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals(1, tokens.get(0).column());
        assertEquals(4, tokens.get(1).column());
        assertEquals("select", tokens.get(1).lexeme());
    }

    @Test
    void nbSelectIsAcceptedInStaticStatementAndDoStatement() {
        Ast.Program ast = Parser.parse("""
                fnc choose(): void {
                  nb-select {
                    default: {}
                  }
                  do nb-select {
                    default: {}
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt first = assertInstanceOf(Ast.SelectStmt.class, fn.body().get(0));
        Ast.SelectStmt second = assertInstanceOf(Ast.SelectStmt.class, fn.body().get(1));
        assertEquals(Ast.WaitMode.NONBLOCKING, first.mode());
        assertEquals(Ast.WaitMode.NONBLOCKING, second.mode());
        assertEquals(false, first.explicitDo());
        assertEquals(true, second.explicitDo());
    }

    @Test
    void arithmeticAndWhitespaceAreNotCompoundKeywords() {
        List<Token> tokens = new Lexer("a-b nb - select nb-selectMore").scan();
        assertEquals(List.of(
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.NB, Token.Type.MINUS, Token.Type.SELECT,
                Token.Type.NB, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc wrong(): void { nb - select { default: {} } }"));
    }
}
