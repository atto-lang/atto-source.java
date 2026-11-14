package dev.oreslang.runtime;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OresSymbolTest {
    @Test void logicalIdentityAndDistinctStringKeys() {
        OresSymbol first = OresSymbol.process("status");
        OresSymbol second = OresSymbol.process("status");
        assertNotSame(first, second); // pointer interning must not be required
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, "status");

        Map<Object, Long> values = new LinkedHashMap<>();
        values.put("status", 1L);
        values.put(first, 2L);
        assertEquals(2, values.size());
        assertEquals(1L, values.get("status"));
        assertEquals(2L, values.get(second));
        values.put(second, 3L);
        assertEquals(2, values.size());
        assertEquals(3L, values.get(first));
    }

    @Test void nfcNormalizationAndKindSeparation() {
        assertEquals(OresSymbol.process("café"), OresSymbol.process("cafe\u0301"));
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertNotEquals(OresSymbol.process(uuid), OresSymbol.stable(uuid));
        assertEquals(OresSymbol.stable(uuid), OresSymbol.stable(uuid));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.stable("550E8400-e29b-41d4-a716-446655440000"));
    }

    @Test void maliciousOrOversizedNamesAreRejectedWithoutInterning() {
        for (String name : List.of(
                "", "\u0000", "\u0085", "\u2028", "\u2029",
                "abc\u202edef", "\ud800", "\udc00", "a".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> OresSymbol.process(name), name);
        }
        assertEquals(256, OresSymbol.process("é".repeat(128)).name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        IllegalArgumentException huge = assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process("a".repeat(10_000)));
        assertTrue(huge.getMessage().contains("normalization budget"));
        // Legitimate canonical equivalents must still normalize identically.
        assertEquals(OresSymbol.process("café"), OresSymbol.process("cafe\u0301"));
    }

    @Test void rawNormalizationBudgetAndLongIdentifierRoundTrip() {
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process("a".repeat(100_000)));
        OresSymbol longName = OresSymbol.process("a".repeat(129));
        String rendered = longName.toString();
        assertTrue(rendered.startsWith("#\""), "unquoted Symbol exceeds lexical limit");
        List<Token> parsed = new Lexer(rendered).scan();
        assertEquals(Token.Type.SYMBOL_LITERAL, parsed.getFirst().type());
        assertEquals(longName.name(), parsed.getFirst().lexeme());
    }

    @Test void taggedPropertyKeyDomainsPreserveCanonicalIdentity() {
        OresPropertyKey text = OresPropertyKey.fromGuestValue("café");
        OresPropertyKey canonical = OresPropertyKey.fromGuestValue(OresSymbol.process("café"));
        OresPropertyKey decomposed = OresPropertyKey.fromGuestValue(OresSymbol.process("cafe\u0301"));
        assertNotEquals(text, canonical);
        assertEquals(canonical, decomposed);
        assertEquals(OresPropertyKey.Domain.STRING, text.domain());
        assertEquals(OresPropertyKey.Domain.PROCESS_SYMBOL, canonical.domain());

        OresPropertyKey stable = OresPropertyKey.fromGuestValue(
                OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"));
        assertEquals(OresPropertyKey.Domain.STABLE_SYMBOL, stable.domain());
        assertNotEquals(OresPropertyKey.fromGuestValue(
                OresSymbol.process("550e8400-e29b-41d4-a716-446655440000")), stable);
        assertThrows(IllegalArgumentException.class, () -> OresPropertyKey.fromGuestValue(1));
        Map<OresPropertyKey, Integer> fields = new LinkedHashMap<>();
        fields.put(text, 1);
        fields.put(canonical, 2);
        assertEquals(2, fields.size());
        assertEquals(2, fields.get(decomposed));
    }

    @Test void onlyPublicCoreSymbolsPassDefaultUntrustedPolicy() {
        assertTrue(OresSymbol.process("ready").publicToUntrustedCoreV1());
        assertTrue(OresSymbol.process("error").publicToUntrustedCoreV1());
        assertFalse(OresSymbol.process("tenant-a/private").publicToUntrustedCoreV1());
        assertFalse(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000").publicToUntrustedCoreV1());
    }

    @Test void renderingAlwaysUsesLexicallyAcceptedHashOrQuotedShape() {
        String shortName = "a".repeat(128);
        String longName = "a".repeat(129);
        assertEquals("#" + shortName, OresSymbol.process(shortName).toString());
        assertEquals("#\"" + longName + "\"", OresSymbol.process(longName).toString());
        assertEquals("#\"café\"", OresSymbol.process("cafe\u0301").toString());
        assertEquals("#\"quote\\\"slash\\\\\"", OresSymbol.process("quote\"slash\\").toString());
        List<Token> roundTrip = new Lexer(OresSymbol.process(longName).toString()).scan();
        assertEquals(Token.Type.SYMBOL_LITERAL, roundTrip.getFirst().type());
        assertEquals(longName, roundTrip.getFirst().lexeme());
        assertEquals("Symbol.stable(\"550e8400-e29b-41d4-a716-446655440000\")",
                OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000").toString());
    }

    @Test void dynamicallyCreatedSymbolNamesRenderAsParseableLiterals() {
        String maxBareName = "a".repeat(128);
        String longIdentifierName = "a".repeat(129);
        assertEquals("#" + maxBareName, OresSymbol.process(maxBareName).toString());
        assertEquals("#\"" + longIdentifierName + "\"", OresSymbol.process(longIdentifierName).toString());
        assertNotSame(OresSymbol.process("ready"), OresSymbol.process("ready"));
        assertEquals(OresSymbol.process("ready"), OresSymbol.process("ready"));
        for (String name : List.of(longIdentifierName, "café", "hello world", "quote\" and slash\\ x")) {
            String rendered = OresSymbol.process(name).toString();
            List<Token> tokens = new Lexer(rendered).scan();
            assertEquals(Token.Type.SYMBOL_LITERAL, tokens.getFirst().type());
            assertEquals(name, tokens.getFirst().lexeme());
            assertEquals(Token.Type.EOF, tokens.get(1).type());
        }
    }

    @Test void hashLiteralScannerKeepsColonAndStringKeysSeparate() {
        List<Token> tokens = new Lexer("foo: #ready, [#\"ready foo\"], #'ready foo', #class, \"ready\"").scan();
        assertEquals(List.of(
                Token.Type.IDENT, Token.Type.COLON, Token.Type.SYMBOL_LITERAL, Token.Type.COMMA,
                Token.Type.LBRACKET, Token.Type.SYMBOL_LITERAL, Token.Type.RBRACKET, Token.Type.COMMA,
                Token.Type.SYMBOL_LITERAL, Token.Type.COMMA, Token.Type.SYMBOL_LITERAL,
                Token.Type.COMMA, Token.Type.STRING, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals("ready", tokens.get(2).lexeme());
        assertEquals("ready foo", tokens.get(5).lexeme());
        assertEquals("class", tokens.get(10).lexeme());
    }

    @Test void hashTokenIsNeverCommentOrWhitespaceAlias() {
        for (String invalid : List.of("#", "# ready", "#123", "#foo".replace("foo", "é"),
                "#" + "a".repeat(129), "#\"\\q\"", "#\"unterminated",
                "#\"\\u202e\"", "#\"\\u0000\"")) {
            assertThrows(IllegalArgumentException.class, () -> new Lexer(invalid).scan(), invalid);
        }
        assertEquals(Token.Type.COLON, new Lexer(":ready").scan().getFirst().type());
    }
}
