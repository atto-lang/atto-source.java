package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void nbSelectWorksInStaticAndDynamicSelection() {
        Ast.Program ast = Parser.parse("""
                fnc choose(): void {
                  nb-select {
                    default: {}
                  }
                  do nb-select {
                    default: {}
                  }
                  val result = nb-select first from cases;
                  return;
                }
                """);
        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt first = assertInstanceOf(Ast.SelectStmt.class, fn.body().get(0));
        Ast.SelectStmt second = assertInstanceOf(Ast.SelectStmt.class, fn.body().get(1));
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, fn.body().get(2));
        Ast.DynamicSelectExpr dynamic =
                assertInstanceOf(Ast.DynamicSelectExpr.class, binding.initializer());
        assertEquals(Ast.WaitMode.NONBLOCKING, first.mode());
        assertEquals(Ast.WaitMode.NONBLOCKING, second.mode());
        assertEquals(false, first.explicitDo());
        assertEquals(true, second.explicitDo());
        assertEquals(Ast.WaitMode.NONBLOCKING, dynamic.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, dynamic.policy());
    }

    @Test
    void contextualCriticalAndPipelineModifiersFailClosed() {
        String[] unsupported = {
            "pub fnc critical-read get(): int { return 1; }",
            "pub critical-read fnc get(): int { return 1; }",
            "pub fnc critical-write put(): void { return; }",
            "pub critical-write fnc put(): void { return; }",
            "define actor A as critical-read peek(): int { return 1; } end",
            "define actor A as critical-write write(): void { return; } end",
            "define actor A as fnc critical-read peek(): int { return 1; } end",
            "define actor A as method critical-write put(): void { return; } end",
            "define class Box as pub critical-read get(): int { return 1; } end",
            "define class Box as pub static fnc critical-write put(): void { return; } end",
            "pub static critical-read fnc nonmember(): int { return 1; }"
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
    void contextualCompoundWordsNeverStealSubtractionFromIdentifiers() {
        // 'critical', 'read', 'transform', and 'pipe' are all valid variable
        // names. The hyphens in expression position remain MINUS tokens.
        assertDoesNotThrow(() -> Parser.parse("""
                fnc difference(int critical, int read, int transform, int pipe): int {
                  return critical-read + transform-pipe;
                }
                """));
        List<Token> tokens = new Lexer("critical-read transform-pipe").scan();
        assertEquals(List.of(
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), tokens.stream().map(Token::type).toList());
    }

    @Test
    void whitespaceNearMissesAndNonAsciiDashesAreNotCompounds() {
        List<Token> fragments = new Lexer(
                "critical-readonly critical-write2 transform-pipex nb-selectMore").scan();
        assertEquals(4L, fragments.stream()
                .filter(token -> token.type() == Token.Type.MINUS).count());
        assertThrows(IllegalArgumentException.class,
                () -> new Lexer("nb" + (char) 0x2013 + "select").scan());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc wrong(): void { nb - select { default: {} } }"));
    }

    @Test
    void subtractionWithReservedNbAndSelectDoesNotGetRewrittenAcrossWhitespace() {
        List<Token> tokens = new Lexer("a-b nb - select nb-selectMore").scan();
        assertEquals(List.of(
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.NB, Token.Type.MINUS, Token.Type.SELECT,
                Token.Type.NB, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), tokens.stream().map(Token::type).toList());
    }
}
