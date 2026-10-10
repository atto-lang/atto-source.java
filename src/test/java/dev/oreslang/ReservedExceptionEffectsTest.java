package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReservedExceptionEffectsTest {
    @Test
    void effectKeywordsCannotBecomeOrdinaryIdentifiers() {
        for (String spelling : new String[]{"throw", "raise", "panic", "recover"}) {
            assertTrue(new Lexer(spelling).scan().getFirst().type()
                    != Token.Type.IDENT, spelling);
            String source = "pub fnc main(): int { " + spelling
                    + " 7; return 1; }";
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(source), spelling);
            assertTrue(rejected.getMessage().contains("exceptional-control effect"),
                    spelling + ": " + rejected.getMessage());
        }
    }

    @Test
    void existingTryCatchFinallyAndDeferContinueToParse() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): int {
                    defer stdio.stdout.write("cleanup");
                    try {
                        return 7;
                    } catch (error) {
                        return 8;
                    } finally {
                        stdio.stdout.write("final");
                    }
                }
                """));
    }
}
