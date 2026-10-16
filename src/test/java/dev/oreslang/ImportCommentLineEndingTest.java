package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Import grammar must see every source line, even with CR-only comments. */
final class ImportCommentLineEndingTest {
    @Test
    void commentsEndAtCrLfAndLfWithoutHidingImportsOrSkewingLineNumbers() {
        for (String ending : List.of("\r", "\r\n", "\n")) {
            String source = "// benign comment" + ending
                    + "import fnc helper from \"./helper.ores\";" + ending
                    + "pub fnc main(): int { return 42; }";
            var tokens = new Lexer(source).scan();
            var firstImport = tokens.stream()
                    .filter(token -> token.type() == Token.Type.IMPORT)
                    .findFirst().orElseThrow();
            assertEquals(2, firstImport.line(), "line ending: " + ending.length());
            var parsed = Parser.parse(source);
            assertEquals(1, parsed.imports().size());
            assertEquals("./helper.ores", parsed.imports().getFirst().path());
        }
    }

    @Test
    void nestedCommentsDoNotCreateFakeImportsButLaterImportsRemainVisible() {
        String source = "/* first /* nested import fnc fake from \"x\"; */ ignored */\r"
                + "import fnc helper from \"./helper.ores\";\r"
                + "pub fnc main(): int { return 42; }";
        var parsed = Parser.parse(source);
        assertEquals(1, parsed.imports().size());
        assertTrue(parsed.imports().getFirst().path().endsWith("helper.ores"));
    }
}
