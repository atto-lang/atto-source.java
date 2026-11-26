package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MoveBangAliasTest {
    @Test void sameAstAndBareIdentifier() {
        String template = "fnc f(): int { val int x = 7; return %s; }";
        assertEquals(Parser.parse(template.formatted("take! x")),
                     Parser.parse(template.formatted("move! x")));
        assertDoesNotThrow(() -> Parser.parse("fnc f(): int { val int move = 7; return move; }"));
    }

    @Test void sameLexicalGate() {
        for (String word : new String[]{"move", "take"}) {
            for (String bad : new String[]{"!x", "!(x)", "!! x", "!"}) {
                var error = assertThrows(IllegalArgumentException.class,
                        () -> new Lexer(word + bad).scan());
                assertTrue(error.getMessage().contains("E_BANG_KEYWORD_WHITESPACE"));
            }
            assertDoesNotThrow(() -> new Lexer(word + "! x").scan());
            assertDoesNotThrow(() -> new Lexer(word + "!\nx").scan());
            assertDoesNotThrow(() -> new Lexer(word + "!=x").scan());
            assertDoesNotThrow(() -> new Lexer(word + "!eq x").scan());
        }
    }

    @Test void affineMoveAdmissionMatchesTake() {
        String template = """
            define class Payload as
                pub let int value = 42;
            end
            pub fnc main(): int {
                val Payload original = new Payload();
                val Payload transferred = %s original;
                return transferred.value;
            }
            """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(template.formatted("take!")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(template.formatted("move!")));
        String bad = template.replace("return transferred.value;", "return original.value;");
        for (String word : new String[]{"take!", "move!"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(bad.formatted(word)));
        }
    }
}
