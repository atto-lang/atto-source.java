package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class BangKeywordWhitespaceTest {
    @Test void everyBangKeywordRequiresSeparator() {
        for (String keyword : List.of("trap!", "throw!", "raise!", "panic!", "copy!",
                "copy!!", "take!", "move!", "borrow!", "share!", "pin!", "drop!", "pipe!",
                "rpipe!", "stop!", "expand!")) {
            for (String tail : List.of("value", "(42)", "/*comment*/ 42", "", "\u00a0value")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> new Lexer(keyword + tail).scan(), keyword + tail);
                assertTrue(e.getMessage().contains("E_BANG_KEYWORD_WHITESPACE"), e::getMessage);
            }
            for (String gap : List.of(" ", "\t", "\n", "\r\n", "\u000b", "\f")) {
                assertDoesNotThrow(() -> new Lexer(keyword + gap + "value").scan());
            }
        }
    }

    @Test void lexicalLookalikesAreNotKeywordSuffixes() {
        for (String source : List.of("copy!=value", "copy!eq value", "!condition",
                "copy !value", "mycopy!value", "\"trap!(42)\"", "// trap!(42)\n42",
                "/* copy!!value */ 42", "#\"trap!(42)\"")) {
            assertDoesNotThrow(() -> new Lexer(source).scan(), source);
        }
        assertThrows(IllegalArgumentException.class, () -> new Lexer("copy!eqfoo").scan());
    }

    @Test void spacedTrapIsAResultBoundaryNotBooleanNegation() {
        String guest = "pub fnc main(): int { val r = trap! (84 / 2); return r.unwrap(); }";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(guest)));
        try (var context = org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }
}
