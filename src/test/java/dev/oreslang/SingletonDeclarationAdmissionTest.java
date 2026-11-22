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
    void statefulTraitAndInlineStateSingletonsRejectAtCompilerOwnedBoundary() {
        // Byte-identical to oreslang-compilers-parity-testing#12 candidates,
        // Git blobs 9f0f0a35db63264e7fd57ffe0a9986333e21f2a6 and
        // 2f9b1c149c051eff1fb96dba795a914a37ed28b8.
        String[] sources = {
                "define singleton StateBank with Stateful<BankState> as\n"
                + "    @Inject\n"
                + "    state: MutableStateContainer<BankState>\n"
                + "end\n",
                "define singleton StateBank implements Stateful<BankState> as\n"
                + "    @Inject\n"
                + "    state: MutableStateContainer<BankState>\n"
                + "end\n",
                "define singleton StateBank as\n"
                + "    state { let count: int = 0 }\n"
                + "end\n"
        };
        for (String source : sources) {
            IllegalArgumentException error = reject(source);
            assertTrue(error.getMessage().contains("ORES-SINGLETON-UNSUPPORTED"),
                    source + ": " + error.getMessage());
        }
        // This must not turn into unconditional rejection of ordinary programs.
        assertDoesNotThrow(() -> Parser.parse("fnc main():int{return 42;}\n"));
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
