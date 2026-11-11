package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RemovedOperatorAdmissionTest {
    @TempDir Path scratch;

    @Test
    void removedArithmeticAndCaretOperatorsFailAtLexicalAdmission() {
        for (String expr : new String[] { "9 % 2", "2 ^ 3", "true ^^ false" }) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> new Lexer("pub fnc main(): int { return " + expr + "; }").scan());
            assertTrue(denied.getMessage().contains("removed operator"), denied::getMessage);
        }
    }

    @Test
    void linkedCompilerRejectsRemovedOperatorsForAttoAndLegacyOres() throws Exception {
        Map<String, String> expressions = Map.of(
                "modulo", "9 % 2",
                "caret", "2 ^ 3",
                "double-caret", "true ^^ false");
        for (String extension : new String[] { ".atto", ".ores" }) {
            for (Map.Entry<String, String> example : expressions.entrySet()) {
                Path source = scratch.resolve(example.getKey() + extension);
                Files.writeString(source,
                        "pub fnc main(): int { return " + example.getValue() + "; }\n");
                RuntimeException denied = assertThrows(RuntimeException.class,
                        () -> LinkedProgramRunner.validate(source, Map.of()));
                assertTrue(denied.getMessage().contains("removed operator"),
                        () -> "missing targeted lexer diagnostic: " + denied.getMessage());
            }
        }
    }

    @Test
    void operatorLikeTextInCommentsAndStringsIsNotARejectedOperator() {
        String source = """
                pub fnc main(): void {
                  // % ^ ^^ are harmless comment text
                  stdio.stdout.println("% ^ ^^");
                  return;
                }
                """;
        assertDoesNotThrow(() -> new Lexer(source).scan());
        assertDoesNotThrow(() -> Parser.parse(source));
    }
}
