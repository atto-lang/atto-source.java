package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SingletonDeclarationAdmissionTest {
    private static IllegalArgumentException reject(String source) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
    }

    @Test
    void singletonDeclarationHasStableFailClosedDiagnostic() {
        for (String source : new String[] {
                "define singleton Settings as end",
                "define pub singleton Settings as end",
                "pub define singleton Settings as end"
        }) {
            IllegalArgumentException error = reject(source);
            assertTrue(error.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("five-backend guest execution parity"),
                    error.getMessage());
        }
    }

    @Test
    void singletonInsideModuleDoesNotBypassCompilerAdmission() {
        for (String declaration : new String[] {
                "define singleton Settings as end",
                "define pub singleton Settings as end",
                "pub define singleton Settings as end"
        }) {
            String source = "define module app as\n" + declaration + "\nend\n";
            IllegalArgumentException error = reject(source);
            assertTrue(error.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                    source + ": " + error.getMessage());
        }
    }

    @Test
    void singletonInCommentsDoesNotImpersonateGuestDeclaration() {
        String source = """
                // define singleton Noop as end
                /* define pub singleton Noop as end */
                fnc singleton(): int { return 42; }
                fnc main(): int { return singleton(); }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void singletonRemainsContextualAndLegalAsOrdinaryIdentifier() {
        String source = """
                // define singleton CommentOnly as end
                fnc singleton(): int { return 42; }
                fnc singleton_count(): int { return singleton(); }
                """;
        assertTrue(new Lexer(source).scan().stream()
                .anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("singleton")));
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void genericUnknownDefineDoesNotMasqueradeAsSingletonDiagnostic() {
        IllegalArgumentException error = reject("define mystery Settings as end");
        assertFalse(error.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                error.getMessage());
    }
}
