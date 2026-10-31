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

    // Generated from the EXACT lexical contract v1 manifest Git blob:
    // e9dbf3b6fad5562b7ca6df631ab3c45f478480ba
    // This is a lexer-only inventory; parsing and lowering are separate gates.
    private static final List<String> PINNED_RESERVED_WORDS = List.of(
            "abstract", "actor", "arr", "as", "async", "await", "block", "break",
            "catch", "cb", "class", "conforms", "const", "continue", "contract", "cpu",
            "def", "default", "defer", "define", "do", "done", "elif", "else",
            "elseif", "end", "eq", "extends", "false", "fi", "finally", "fnc",
            "for", "from", "generator", "gpu", "if", "impl", "implements", "import",
            "interface", "is", "isoactor", "let", "loop", "match", "matches", "module",
            "mut", "namespace", "nb", "neq", "new", "nlex", "null", "obj",
            "of", "over", "panic", "private", "pub", "quantum", "raise", "readch",
            "recover", "return", "routine", "rt", "select", "self", "shared", "spawn",
            "static", "stop", "struct", "super", "suspends", "switch", "then", "throw",
            "trait", "trap", "true", "try", "type", "typeof", "types", "untrusted",
            "val", "void", "while", "writech", "yield"
    );

    @Test
    void checkedInManifestExactlyMatchesThePinnedSourceGitBlobAndKeywordInventory()
            throws Exception {
        byte[] raw;
        try (var stream = getClass().getResourceAsStream(
                "/contracts/editor-syntax/v1/editor-lexical-contract.json")) {
            assertNotNull(stream, "shared lexical contract test resource must exist");
            raw = stream.readAllBytes();
        }

        var sha1 = java.security.MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + raw.length + "\0")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String gitBlob = java.util.HexFormat.of().formatHex(sha1.digest(raw));
        assertEquals("e9dbf3b6fad5562b7ca6df631ab3c45f478480ba", gitBlob,
                "exact source manifest bytes must be preserved");

        String json = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        var keyMatch = java.util.regex.Pattern.compile(
                "\"reservedWords\"\\s*:\\s*\\[(.*?)\\]",
                java.util.regex.Pattern.DOTALL).matcher(json);
        assertTrue(keyMatch.find(), "contract must contain reservedWords array");
        var words = java.util.regex.Pattern.compile("\"([a-z]+)\"")
                .matcher(keyMatch.group(1))
                .results().map(match -> match.group(1)).toList();
        assertEquals(PINNED_RESERVED_WORDS, words,
                "Java lexer inventory must match byte-pinned manifest");
        assertTrue(json.contains("NOT parser or backend acceptance"),
                "lexical contract must not imply compiler/backend conformance");
    }

    @Test
    void everyPinnedReservedWordHasAnActualNonIdentifierLexerToken() {
        assertEquals(93, PINNED_RESERVED_WORDS.size());
        assertEquals(93, new java.util.HashSet<>(PINNED_RESERVED_WORDS).size());
        assertEquals(PINNED_RESERVED_WORDS.stream().sorted().toList(), PINNED_RESERVED_WORDS);
        for (String word : PINNED_RESERVED_WORDS) {
            List<Token> tokens = lex(word);
            assertEquals(2, tokens.size(), word);
            assertNotEquals(IDENT, tokens.get(0).type(), word);
            assertEquals(word, tokens.get(0).lexeme(), word);
            assertEquals(EOF, tokens.get(1).type(), word);
        }
        // Synonymous spellings are deliberately not distinct token types.
        assertEquals(ELSEIF, lex("elif").get(0).type());
        assertEquals(ELSEIF, lex("elseif").get(0).type());
    }

    @Test
    void contextualIdentifierLexingDoesNotInventFutureKeywords() {
        for (String identifier : List.of("owned", "borrow", "pipe", "read", "write", "transform")) {
            // Compound forms are recognized only in parser context.
            assertEquals(List.of(IDENT, EOF), types(identifier), identifier);
        }
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
