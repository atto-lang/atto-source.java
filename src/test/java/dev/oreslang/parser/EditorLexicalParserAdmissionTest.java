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

    /**
     * Exact corpus PRNG shared with the web highlighter's deterministic tests:
     * 32-bit LCG overflow, unsigned modulus (not java.util.Random).
     */
    private static final class SeededCorpus {
        private int state;

        SeededCorpus(int seed) { this.state = seed; }

        int nextIndex(int bound) {
            state = state * 1_664_525 + 1_013_904_223;
            return Integer.remainderUnsigned(state, bound);
        }
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
            // Every reserved spelling has one exact Token.Type; a wrong
            // non-IDENT mapping must fail this conformance gate too.
            Token.Type expected = Token.Type.valueOf(
                    (word.equals("elif") ? "elseif" : word)
                            .toUpperCase(java.util.Locale.ROOT));
            assertEquals(expected, tokens.get(0).type(), word);
            assertEquals(word, tokens.get(0).lexeme(), word);
            assertEquals(EOF, tokens.get(1).type(), word);
        }
        // Synonymous spellings are deliberately not distinct token types.
        assertEquals(ELSEIF, lex("elif").get(0).type());
        assertEquals(ELSEIF, lex("elseif").get(0).type());
    }

    @Test
    void reservedWordsInsideLongerIdentifiersAreNotKeywords() {
        for (String word : PINNED_RESERVED_WORDS) {
            // Word boundaries are lexical: \`return_x\` and \`returnx\` do
            // not become a RETURN token, even for legacy reserved spellings.
            assertEquals(List.of(IDENT, EOF), types(word + "x"),
                    "suffix identifier containing " + word);
            assertEquals(List.of(IDENT, EOF), types("_" + word),
                    "prefix identifier containing " + word);
            assertEquals(List.of(IDENT, EOF), types(word + "_suffix"),
                    "underscored identifier containing " + word);
        }
    }

    @Test
    void quotedSymbolSurrogatePairEscapesNormalizeButHalfPairsReject() {
        assertEquals("😀", lex("#\"\\uD83D\\uDE00\"").get(0).lexeme());
        assertEquals("😀", lex("#'\\uD83D\\uDE00'").get(0).lexeme());
        for (String bad : List.of(
                "#\"\\uD83D\"", "#\"\\uDE00\"",
                "#\"\\uDE00\\uD83D\"", "#\"\\uD83D\\u0061\"")) {
            assertThrows(IllegalArgumentException.class, () -> lex(bad), bad);
        }
        assertEquals(List.of(SYMBOL_LITERAL, EOF),
                types("#\"\\uD83D\\uDE00\""));
    }

    @Test
    void bmpUnicodeLetterIdentifiersDoNotSplitIntoReservedWordTokens() {
        for (String name : List.of(
                "éreturn", "returné", "中eq", "eq中",
                "πpanic", "panicπ", "αgpu", "gpuα",
                "éreturn_suffix", "return_中")) {
            assertEquals(List.of(IDENT, EOF), types(name),
                    "BMP Unicode letter identifier: " + name);
            assertEquals(List.of(IDENT, RETURN, EOF), types(name + " return"),
                    "a separate reserved word must still be recognized: " + name);
        }
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
    void symbolUnicodeNormalizationEscapesAndUtf8BudgetsAreCompilerChecked() {
        // A decomposed canonical character is normalized exactly once.
        assertEquals("é", lex("#\"e\u0301\"").get(0).lexeme());
        assertEquals("a", lex("#\"\\u0061\"").get(0).lexeme());
        assertEquals("a", lex("#'\\u0061'").get(0).lexeme());

        // 128 ASCII bytes is the inclusive unquoted limit.
        String atLimit = "a".repeat(128);
        assertEquals(atLimit, lex("#" + atLimit).get(0).lexeme());

        // The quoted limit counts NFC-encoded UTF-8 bytes, not UTF-16 units.
        String exact256 = "a".repeat(254) + "é";
        assertEquals(exact256, lex("#\"" + exact256 + "\"").get(0).lexeme());
        String over256 = exact256 + "b";
        assertThrows(IllegalArgumentException.class,
                () -> lex("#\"" + over256 + "\""));

        // Admission must be bounded during decoding, before the closing quote.
        // Earlier builds accumulated the entire attacker-controlled literal
        // and only then invoked OresSymbol's normalization budget check.
        IllegalArgumentException hugeName = assertThrows(IllegalArgumentException.class,
                () -> lex("#\"" + "a".repeat(250_000)));
        assertTrue(hugeName.getMessage().contains("normalization budget"),
                "oversized quoted Symbol must reject before scanning to EOF");

        // Decoded controls, invisible format characters and malformed UTF-16
        // are rejected even when their source is ASCII escape text.
        for (String source : List.of(
                "#\"\\n\"", "#\"\\r\"", "#\"\\t\"",
                "#\"\\u0000\"", "#\"\\u202e\"", "#\"\\uD800\"")) {
            assertThrows(IllegalArgumentException.class, () -> lex(source), source);
        }
    }

    @Test
    void nestedCommentsAndNearbyCompoundPrefixesRemainLexerBounded() {
        String nested = "/*".repeat(128) + "throw #ready" + "*/".repeat(128) + " return";
        assertEquals(List.of(RETURN, EOF), types(nested));
        assertThrows(IllegalArgumentException.class, () -> lex("/* outer /* inner */"));

        // Inclusive 4,096 comment depth, then a bounded hard rejection.
        String maxComment = "/*".repeat(4_096) + "throw #ready"
                + "*/".repeat(4_096) + " return";
        assertEquals(List.of(RETURN, EOF), types(maxComment));
        String tooDeep = "/*".repeat(4_097) + "throw #ready"
                + "*/".repeat(4_097);
        IllegalArgumentException deepError = assertThrows(
                IllegalArgumentException.class, () -> lex(tooDeep));
        assertTrue(deepError.getMessage().contains("nesting limit"));

        // The nested-comment limit applies inside template interpolation too.
        String backtick = String.valueOf((char) 96);
        String interpolation = backtick + "$" + "{" + "/*".repeat(4_097)
                + "ignored" + "*/".repeat(4_097) + " 1 }" + backtick;
        IllegalArgumentException templateError = assertThrows(
                IllegalArgumentException.class, () -> lex(interpolation));
        assertTrue(templateError.getMessage().contains("nesting limit"));

        // 'nb-select' is a special lexer compound; a longer identifier must
        // not be consumed as an accidental matching prefix.
        assertEquals(List.of(NB, MINUS, IDENT, EOF), types("nb-selector"));
        assertEquals(List.of(NB, MINUS, IDENT, EOF), types("nb-select_extra"));
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
    void deterministicQuotedSymbolUnicodeFuzzMatchesRuntimeNormalization() {
        SeededCorpus rng = new SeededCorpus(0x5A17C0DE);
        int[] scalars = { 'a', 'z', '_', '7', 0x00e9, 0x4e2d, 0x1f600, 'e', 0x0301 };
        for (int caseNo = 0; caseNo < 384; caseNo++) {
            StringBuilder name = new StringBuilder();
            int length = 1 + rng.nextIndex(32);
            for (int j = 0; j < length; j++) {
                name.appendCodePoint(scalars[rng.nextIndex(scalars.length)]);
            }
            String decoded = name.toString();
            String expected = dev.oreslang.runtime.OresSymbol.normalizeAndValidate(decoded);
            List<Token> actual = lex("#\"" + decoded + "\" return");
            assertEquals(List.of(SYMBOL_LITERAL, RETURN, EOF), actual.stream().map(Token::type).toList(),
                    "quoted Symbol token types, generated case " + caseNo);
            assertEquals(expected, actual.get(0).lexeme(), "generated case " + caseNo);
        }
    }

    @Test
    void deterministicOpaqueNestedCommentsNeverLeakReservedWordsOrSymbols() {
        SeededCorpus rng = new SeededCorpus(0xC011AB1E);
        for (int caseNo = 0; caseNo < 256; caseNo++) {
            int depth = 1 + rng.nextIndex(256);
            String body = "/*".repeat(depth) + " throw raise #ready \"return\" "
                    + "*/".repeat(depth) + " return";
            assertEquals(List.of(RETURN, EOF), types(body),
                    "comment opacity, generated case " + caseNo);
        }
    }

    @Test
    void deterministicMalformedUtf16FuzzFailsInsideOpaqueLexicalRegions() {
        SeededCorpus rng = new SeededCorpus(0x0BADC0DE);
        String loneHigh = String.valueOf((char) 0xd800);
        String loneLow = String.valueOf((char) 0xdc00);
        String backtick = String.valueOf((char) 96);
        String[] surrounds = { "", "/*", "//", "\"", "'", backtick, "#\"", "return " };
        for (int caseNo = 0; caseNo < 384; caseNo++) {
            String invalid = (rng.nextIndex(2) == 0 ? loneHigh : loneLow);
            String source = surrounds[rng.nextIndex(surrounds.length)]
                    + "a".repeat(rng.nextIndex(24)) + invalid
                    + "b".repeat(rng.nextIndex(24)) + " return";
            assertThrows(IllegalArgumentException.class, () -> lex(source),
                    "malformed UTF-16 must fail globally, generated case " + caseNo);
        }
    }

    /** Decode exact UTF-16 code units; preserves malformed host-string cases. */
    private static String fromUtf16Hex(String hex) {
        assertTrue(hex.length() > 0 && hex.length() <= 65_536
                && hex.length() % 4 == 0, "bounded UTF-16 hex fixture");
        StringBuilder decoded = new StringBuilder(hex.length() / 4);
        for (int i = 0; i < hex.length(); i += 4) {
            decoded.append((char) Integer.parseInt(hex.substring(i, i + 4), 16));
        }
        return decoded.toString();
    }

    @Test
    void pinnedDifferentialCorpusRunsAgainstActualJavaLexer() throws Exception {
        byte[] fixture;
        try (var stream = getClass().getResourceAsStream(
                "/contracts/editor-syntax/v1/compiler-lexical-differential-v1.tsv")) {
            assertNotNull(stream, "portable differential corpus is required");
            fixture = stream.readAllBytes();
        }
        assertTrue(fixture.length < 64 * 1024, "corpus exceeds size budget");
        var git = java.security.MessageDigest.getInstance("SHA-1");
        git.update(("blob " + fixture.length + "\0")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("eeed64b7d33c262b35120662386435575982a9bc",
                java.util.HexFormat.of().formatHex(git.digest(fixture)),
                "exact synthetic corpus blob must match the shared contract");
        var entries = new java.util.HashSet<String>();
        int executed = 0, rejected = 0;
        for (String line : new String(fixture, java.nio.charset.StandardCharsets.UTF_8)
                .split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            assertEquals(5, fields.length, "case must have 5 fields");
            String id = fields[0];
            assertTrue(id.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), id);
            assertTrue(entries.add(id), "duplicate differential case: " + id);
            String source = fromUtf16Hex(fields[1]);
            if (fields[2].equals("ERROR")) {
                assertEquals("-", fields[3], id);
                assertNotEquals("-", fields[4], id);
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class, () -> lex(source), id);
                assertTrue(failure.getMessage().contains(fields[4]),
                        id + ": unexpected error " + failure.getMessage());
                rejected++;
            } else {
                assertEquals("-", fields[4], id);
                List<Token.Type> expected = java.util.Arrays.stream(fields[2].split(","))
                        .map(Token.Type::valueOf).toList();
                assertEquals(expected, types(source), id);
                if (!fields[3].equals("-")) {
                    Token symbol = lex(source).stream()
                            .filter(token -> token.type() == SYMBOL_LITERAL)
                            .findFirst().orElseThrow();
                    assertEquals(fromUtf16Hex(fields[3]), symbol.lexeme(), id);
                }
            }
            executed++;
        }
        assertEquals(34, executed, "all 34 exact shared oracle inputs must execute");
        assertEquals(11, rejected, "all invalid-source negatives must reject");
    }

    @Test
    void executionDomainReservationFailsClosedAtParserAdmission() {
        for (String source : List.of("cpu { }", "gpu { }", "quantum task { }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
