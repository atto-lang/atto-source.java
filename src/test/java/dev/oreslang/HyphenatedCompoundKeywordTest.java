package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void dynamicNbSelectRetainsNonblockingPolicy() {
        Ast.Program ast = Parser.parse("""
                fnc choose(): void {
                  val result = nb-select first from cases;
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt local = assertInstanceOf(Ast.BindingStmt.class, fn.body().get(0));
        Ast.DynamicSelectExpr select =
                assertInstanceOf(Ast.DynamicSelectExpr.class, local.initializer());
        assertEquals(Ast.WaitMode.NONBLOCKING, select.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, select.policy());
    }

    @Test
    void reservedCompoundTokensNeverDowngradeToOrdinaryNames() {
        List<Token> tokens = new Lexer(
                "critical-read critical-write transform-pipe nb-select").scan();
        assertEquals(List.of(
                Token.Type.CRITICAL_READ, Token.Type.CRITICAL_WRITE,
                Token.Type.TRANSFORM_PIPE, Token.Type.NB, Token.Type.SELECT,
                Token.Type.EOF), tokens.stream().map(Token::type).toList());
        assertEquals("critical-read", tokens.get(0).lexeme());
        assertEquals("critical-write", tokens.get(1).lexeme());
        assertEquals("transform-pipe", tokens.get(2).lexeme());

        String[] unsupported = {
            "pub fnc critical-read get(): int { return 1; }",
            "pub critical-read fnc get(): int { return 1; }",
            "pub fnc critical-write put(): void { return; }",
            "pub critical-write fnc put(): void { return; }",
            "define actor A as critical-read peek(): int { return 1; } end",
            "define actor A as critical-write write(): void { return; } end"
        };
        for (String source : unsupported) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("reader/writer critical declaration requires"),
                    failure::getMessage);
        }

        IllegalArgumentException pipeline = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define transform-pipe actor Transform as end"));
        assertTrue(pipeline.getMessage().contains("transform-pipe actor requires"));
    }

    @Test
    void compoundKeywordsRequireExactAsciiHyphenAndWordBoundary() {
        List<Token> tokens = new Lexer(
                "critical-readonly critical-write2 transform-pipex nb-selectMore " +
                "critical - read 7-3").scan();
        // A non-ASCII en dash is deliberately an unsupported lexical character.
        // The preceding fragments must never turn into compound tokens.
        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals(Token.Type.MINUS, tokens.get(1).type());
        assertEquals(Token.Type.IDENT, tokens.get(2).type());
        assertEquals(Token.Type.IDENT, tokens.get(3).type());
        assertEquals(Token.Type.MINUS, tokens.get(4).type());
        assertEquals(Token.Type.IDENT, tokens.get(5).type());
        assertThrows(IllegalArgumentException.class,
                () -> new Lexer("nb" + (char) 0x2013 + "select").scan());
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
