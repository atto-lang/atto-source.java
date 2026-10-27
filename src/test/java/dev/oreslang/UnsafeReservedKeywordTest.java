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
                "define actor A as receive(unsafe: int): void { return; } end"
        };
        for (String source : unsupported) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("unsafe is reserved until checked unsafe effects"),
                    failure::getMessage);
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
}
