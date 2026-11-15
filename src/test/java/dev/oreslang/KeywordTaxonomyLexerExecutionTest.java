package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production Java Lexer token tests from exact source-inspected keyword inventory.
 * This is lexical admission evidence, not parser lowering, guest execution or
 * proof of agreement with the native LLVM reserved-name policy.
 */
final class KeywordTaxonomyLexerExecutionTest {
    private static final List<String> WORDS = Arrays.asList("abstract", "actor", "arr", "as", "async", "await", "block", "break", "catch", "cb", "class", "conforms", "const", "continue", "contract", "cpu", "def", "default", "defer", "define", "do", "done", "elif", "else", "elseif", "end", "eq", "extends", "false", "fi", "finally", "fnc", "for", "from", "generator", "gpu", "guard", "if", "impl", "implements", "import", "interface", "is", "isoactor", "let", "loop", "match", "matches", "module", "mut", "namespace", "nb", "neq", "new", "nlex", "null", "obj", "of", "over", "panic", "private", "pub", "quantum", "raise", "readch", "recover", "return", "routine", "rt", "select", "self", "shared", "spawn", "static", "stop", "struct", "super", "suspends", "switch", "then", "throw", "trait", "trap", "true", "try", "type", "typeof", "types", "unsafe", "untrusted", "val", "void", "while", "writech", "yield");

    private static List<Token.Type> types(String source) {
        return new Lexer(source).scan().stream().map(Token::type).toList();
    }

    @Test
    void allNinetyFiveDocumentedJavaKeywordsAreReallyReserved() {
        assertEquals(95, WORDS.size());
        assertEquals(95, WORDS.stream().distinct().count());
        List<Token> tokens = new Lexer(String.join(" ", WORDS)).scan();
        assertEquals(WORDS.size() + 1, tokens.size());
        for (int i = 0; i < WORDS.size(); i++) {
            assertEquals(WORDS.get(i), tokens.get(i).lexeme(), WORDS.get(i));
            assertNotEquals(Token.Type.IDENT, tokens.get(i).type(), WORDS.get(i));
            assertNotEquals(Token.Type.EOF, tokens.get(i).type(), WORDS.get(i));
        }
        assertEquals(Token.Type.EOF, tokens.getLast().type());
    }

    @Test
    void guardAndUnsafeAreReservedButTargetAndNativeOnlyWordsAreNot() {
        assertEquals(List.of(Token.Type.GUARD, Token.Type.UNSAFE, Token.Type.EOF),
                types("guard unsafe"));
        for (String word : List.of("declare", "sealed", "singleton", "pipe",
                "suspend", "borrow", "and", "or", "enum", "critical", "structural")) {
            assertEquals(List.of(Token.Type.IDENT, Token.Type.EOF),
                    types(word), word);
        }
    }

    @Test
    void keywordWordBoundariesRemainCaseSensitive() {
        for (String word : List.of("actor", "implements", "guard", "unsafe", "suspends")) {
            for (String candidate : List.of(word + "_name", word + "2",
                    "prefix_" + word, word.toUpperCase())) {
                assertEquals(List.of(Token.Type.IDENT, Token.Type.EOF),
                        types(candidate), candidate);
            }
        }
    }

    @Test
    void nbSelectIsTwoRealTokensButCriticalFormsRemainSubtraction() {
        assertEquals(List.of(Token.Type.NB, Token.Type.SELECT, Token.Type.EOF),
                types("nb-select"));
        List<Token> selectTokens = new Lexer("nb-select").scan();
        assertEquals("nb", selectTokens.get(0).lexeme());
        assertEquals("select", selectTokens.get(1).lexeme());
        assertEquals(1, selectTokens.get(0).column());
        assertEquals(4, selectTokens.get(1).column());
        assertEquals(List.of(Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), types("critical-read"));
        assertEquals(List.of(Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), types("transform-pipe"));
        assertEquals(List.of(Token.Type.NB, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.EOF), types("nb-select_more"));
    }

    @Test
    void quotedTextCommentsAndSymbolsNeverBecomeKeywordTokens() {
        assertEquals(List.of(Token.Type.STRING, Token.Type.SYMBOL_LITERAL,
                Token.Type.SYMBOL_LITERAL, Token.Type.EOF),
                types("\"actor\" #unsafe #\"guard\" // implements"));
        assertEquals(List.of(Token.Type.ACTOR, Token.Type.EOF),
                types("/* outer /* implements */ guard */ actor"));
        assertEquals(List.of(Token.Type.STRING, Token.Type.EOF),
                types("\"guard implements unsafe\""));
    }

    @Test
    void javaUnicodeIdentifiersAreDistinctFromAsciiSymbolNames() {
        for (String word : List.of("π_thing", "λ", "café", "Дело")) {
            assertEquals(List.of(Token.Type.IDENT, Token.Type.EOF), types(word), word);
        }
        assertThrows(IllegalArgumentException.class, () -> new Lexer("#π").scan());
        assertEquals(List.of(Token.Type.SYMBOL_LITERAL, Token.Type.EOF),
                types("#\"π\""));
    }

    @Test
    void invalidUnicodeDashAndNestingAreRejected() {
        for (String dash : List.of("–", "—", "−")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Lexer("critical" + dash + "read").scan(), dash);
        }
        String within = "/*".repeat(128) + "actor" + "*/".repeat(128);
        assertEquals(List.of(Token.Type.EOF), types(within));
        String over = "/*".repeat(129) + "actor" + "*/".repeat(129);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Lexer(over).scan());
        assertTrue(failure.getMessage().contains("block comment nesting exceeds 128"));
    }
}
