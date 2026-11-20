package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G0 only: reserve proposed unsafe syntax, with NO source-level execution support.
 * This does not demonstrate effect inference, FFI authority, or AOT lowering.
 */
final class UnsafeReservedKeywordTest {
    @Test
    void unsafeIsNeverAnOrdinaryIdentifier() {
        List<Token> tokens = new Lexer("unsafe #unsafe \"unsafe\"").scan();
        assertEquals(List.of(Token.Type.UNSAFE, Token.Type.SYMBOL_LITERAL,
                Token.Type.STRING, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals("unsafe", tokens.get(1).lexeme());
    }

    @Test
    void rejectsUncheckedEntryPointsBeforeAnySourceExecution() {
        String[] unsupported = {
                "unsafe fnc native_call(): void { return; }",
                "fnc f(): void { unsafe { return; } }",
                "fnc f(): void { val p = unsafe; return; }",
                "fnc unsafe(): void { return; }",
                "fnc f(unsafe: int): int { return 1; }",
                "fnc f(): void { val unsafe: int = 1; return; }",
                "define class UnsafeExample as unsafe read(): void { return; } end",
                "define actor A as receive(unsafe: int): void { return; } end",
                "fnc good(): int { return 42; } unsafe",
                "fnc f(): int { return std.unsafe.memory.read(); }",
                "@unsafe fnc f(): int { return 42; }",
                "fnc good(): int { return 42; } // ignored\runsafe",
                "fnc good(): int { return 42; } // ignored\r\nunsafe"
        };
        for (String source : unsupported) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("unsafe is reserved until checked unsafe effects"),
                    failure::getMessage);
        }
    }

    @Test
    void externallyConstructedTokensCannotForgeUnsafeIdentifier() {
        List<Token> forged = List.of(
                new Token(Token.Type.IDENT, "unsafe", 6, 9),
                new Token(Token.Type.EOF, "", 6, 15));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new Parser(forged));
        assertTrue(rejected.getMessage().contains("6:9"), rejected::getMessage);
        assertTrue(rejected.getMessage().contains("unsafe is reserved"), rejected::getMessage);

        assertThrows(IllegalArgumentException.class, () -> new Parser(List.of(
                new Token(Token.Type.UNSAFE, "unsafe", 2, 1),
                new Token(Token.Type.EOF, "", 2, 7))));
        assertDoesNotThrow(() -> new Parser(List.of(
                new Token(Token.Type.STRING, "unsafe", 1, 1),
                new Token(Token.Type.SYMBOL_LITERAL, "unsafe", 1, 10),
                new Token(Token.Type.EOF, "", 1, 17))));
    }

    @Test
    void reservedWordDoesNotConsumeLongerOrdinaryNames() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc unsafe_helper(): int {
                    const unsafe_count: int = 42;
                    return unsafe_count;
                }
                """));
    }

    @Test
    void templateInterpolationsStillRejectReservedUnsafe() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): string { return `value ${unsafe}`; }"));
        assertTrue(failure.getMessage().contains("unsafe is reserved until checked unsafe effects"),
                failure::getMessage);
    }

    @Test
    void lineCommentsNeverSwallowUnsafeOnCrOnlyLines() {
        for (String ending : List.of("\r", "\n", "\r\n")) {
            String source = "fnc good(): int { return 42; } // comment"
                    + ending + "unsafe fnc bad(): void { return; }";
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source));
            assertTrue(error.getMessage().contains(
                    "unsafe is reserved until checked unsafe effects"),
                    error::getMessage);
        }
        assertDoesNotThrow(() -> Parser.parse(
                "fnc good(): int { return 42; } // unsafe\r"));
    }

    @Test
    void crAndCrlfProduceCorrectUnsafeTokenLocations() {
        for (String newline : List.of("\r", "\r\n", "\n")) {
            for (String prefix : List.of("// comment", "/* comment */",
                    "`literal text`", "\"literal text\"")) {
                var tokens = new Lexer(prefix + newline + "unsafe").scan();
                Token unsafe = tokens.stream().filter(t -> t.type() == Token.Type.UNSAFE)
                        .findFirst().orElseThrow();
                assertEquals(2, unsafe.line(), prefix + " newline=" + newline);
                assertEquals(1, unsafe.column(), prefix + " newline=" + newline);
            }
            var inBlock = new Lexer("/* one" + newline + "two */ unsafe").scan();
            Token unsafe = inBlock.stream().filter(t -> t.type() == Token.Type.UNSAFE)
                    .findFirst().orElseThrow();
            assertEquals(2, unsafe.line());
        }
    }

    @Test
    void doesNotFlagOrdinaryTextCommentsOrSymbolLiterals() {
        assertDoesNotThrow(() -> Parser.parse(
                "fnc safe(): string { /* unsafe */ return \"unsafe\"; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc literal(): Symbol { return #unsafe; }"));
        List<Token> tokens = new Lexer("`unsafe body without interpolation`").scan();
        assertEquals(Token.Type.TEMPLATE_LITERAL, tokens.getFirst().type());
    }

    @Test
    void blockCommentDepthIsBoundedAndCannotHideRealUnsafeTokens() {
        String withinBudget = "/*".repeat(128) + "unsafe" + "*/".repeat(128);
        assertEquals(List.of(Token.Type.EOF),
                new Lexer(withinBudget).scan().stream().map(Token::type).toList());

        String tooDeep = "/*".repeat(129) + "unsafe" + "*/".repeat(129);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new Lexer(tooDeep).scan());
        assertTrue(rejected.getMessage().contains("block comment nesting exceeds 128"),
                rejected::getMessage);

        String afterComment = withinBudget + " unsafe";
        var tokens = new Lexer(afterComment).scan();
        assertEquals(List.of(Token.Type.UNSAFE, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(afterComment));
        assertTrue(denied.getMessage().contains("unsafe is reserved"),
                denied::getMessage);
    }
}
