package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Raw-source terminal control contract; host compiler only, not guest execution. */
final class LexerTerminalControlBoundaryTest {
    @Test
    void quotedSymbolsAndRawTemplatesCannotHideEscOrDel() {
        String tick = Character.toString(0x60);
        for (char control : new char[] {(char) 0x001b, (char) 0x007f}) {
            for (String source : new String[] {
                    "#'" + control + "'",
                    "#\"" + control + "\"",
                    tick + control + tick,
                    "// hidden" + control + "\npub fnc main(): int { return 42; }"
            }) {
                var error = assertThrows(IllegalArgumentException.class,
                        () -> new Lexer(source).scan());
                assertTrue(error.getMessage().contains("source contains terminal control"),
                        "raw ESC/DEL must fail before Symbol/template/comment lexing");
                assertFalse(error.getMessage().contains(Character.toString(control)),
                        "diagnostic must not print raw terminal control");
            }
        }
    }

    @Test
    void visibleUnicodeEscapesRemainDistinctFromRawSourceControls() {
        // Construct the backslash and hex digits separately so the
        // Java compiler cannot translate them as a Java Unicode escape.
        for (int codePoint : new int[] {0x001b, 0x007f}) {
            String hex = String.format("%04x", codePoint);
            String text = "\"" + "\\" + "u" + hex + "\"";
            Token token = new Lexer(text).scan().getFirst();
            assertEquals(Token.Type.STRING, token.type());
            assertEquals(Character.toString(codePoint), token.lexeme());
        }
    }

    @Test
    void precedenceAndColumnsRemainCorrectAfterAstralText() {
        String emoji = Character.toString(0x1f600);
        var beforeNul = assertThrows(IllegalArgumentException.class,
                () -> new Lexer("/* " + emoji + " */" + (char) 0x001b + "\0").scan());
        assertEquals("Oreslang lexer error at 1:9: source contains terminal control",
                beforeNul.getMessage());

        var afterNul = assertThrows(IllegalArgumentException.class,
                () -> new Lexer("\r\n\0" + (char) 0x007f).scan());
        assertEquals("Oreslang lexer error at 2:1: source contains NUL byte",
                afterNul.getMessage());
    }
}
