package dev.oreslang.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.parser.Token.Type.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-local source-of-truth checks for editor lexical contract v1.
 *
 * Lexer classification does NOT imply parser, typechecker, runtime, or
 * other backend acceptance.
 */
final class EditorLexicalParserAdmissionTest {
    private static List<Token> lex(String text) {
        return new Lexer(text).scan();
    }

    private static List<Token.Type> types(String text) {
        return lex(text).stream().map(Token::type).toList();
    }

    private static String routineWith(String statements) {
        return "pub routine main(): void { " + statements + " return; }";
    }

    @Test
    void reservedComparisonsAreNotBindingIdentifiers() {
        assertEquals(List.of(EQ, NEQ, IS, EOF), types("eq neq is"));
        assertDoesNotThrow(() -> Parser.parse(routineWith("val int owned = 6;")));
        for (String word : List.of("eq", "neq", "is")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(routineWith("val int " + word + " = 6;")),
                    "reserved comparison word: " + word);
        }
    }

    @Test
    void historicalAndExecutionWordsRemainReservedByLexer() {
        assertEquals(List.of(ISOACTOR, NULL, PANIC, CPU, GPU, EOF),
                types("isoactor null panic cpu gpu"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(routineWith("val int null = 1;")));
    }

    @Test
    void arithmeticTokensAreNotParserAcceptanceEvidence() {
        assertEquals(List.of(IDENT, PERCENT, IDENT, CARET, IDENT, EOF),
                types("a % b ^ c"));
    }

    @Test
    void hashSymbolsAreAtomicAndQuotedNamesAreDecoded() {
        List<Token> tokens = lex("#ready #'with spaces' #\"quoted value\"");
        assertEquals(List.of(SYMBOL_LITERAL, SYMBOL_LITERAL, SYMBOL_LITERAL, EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals(List.of("ready", "with spaces", "quoted value"),
                tokens.subList(0, 3).stream().map(Token::lexeme).toList());
        assertDoesNotThrow(() -> Parser.parse(routineWith("val symbol = #ready;")));
        assertDoesNotThrow(() -> Parser.parse(routineWith("val symbol = #\"with spaces\";")));
    }

    @Test
    void malformedHashSymbolsFailLexerBeforeParser() {
        for (String text : List.of(
                "#", "# ready", "#123", "#\"\"", "#''", "#\"unterminated",
                "#\"bad\\q\"", "#\"bad\\uZZZZ\"", "#\"\\uD800\"", "#\"\\u202e\"",
                "#\"" + "é".repeat(129) + "\"",
                "#" + "a".repeat(129),
                "#\"raw\nnewline\"")) {
            assertThrows(IllegalArgumentException.class,
                    () -> lex(text), "invalid Symbol: " + text);
        }
    }

    @Test
    void lexerDoesNotLeakKeywordsFromNestedCommentsOrTemplateBodies() {
        assertEquals(List.of(RETURN, EOF),
                types("/* outer /* inner */ throw #ready */ return"));
        String quote = String.valueOf((char) 96);
        List<Token> tokens = lex(quote + "if { return }" + quote + " return");
        assertEquals(List.of(TEMPLATE_LITERAL, RETURN, EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals("if { return }", tokens.get(0).lexeme());
    }

    @Test
    void contextualHyphenSpellingsAreDistinguishedFromLexerCompounds() {
        assertEquals(List.of(NB, SELECT, EOF), types("nb-select"));
        assertEquals(List.of(IDENT, MINUS, IDENT, EOF), types("critical-read"));
        assertEquals(List.of(IDENT, MINUS, IDENT, EOF), types("critical-write"));
        assertEquals(List.of(IDENT, MINUS, IDENT, EOF), types("transform-pipe"));
        assertEquals(List.of(RT, IDENT, MUT, EOF), types("rt borrow mut"));
    }

    @Test
    void lexerRejectsMalformedHostStringsEvenInsideOpaqueBodies() {
        String high = String.valueOf((char) 0xd800);
        String low = String.valueOf((char) 0xdc00);
        for (String text : List.of("/* \0 */", "\"" + high + "\"", "// " + low,
                "/* " + high + " */")) {
            assertThrows(IllegalArgumentException.class, () -> lex(text));
        }
        assertEquals(List.of(STRING, EOF), types("\"😀\""));
    }

    @Test
    void executionDomainReservationFailsClosedAtParserAdmission() {
        for (String source : List.of("cpu { }", "gpu { }", "quantum task { }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
