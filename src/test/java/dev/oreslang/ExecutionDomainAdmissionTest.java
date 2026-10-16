package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionDomainAdmissionTest {
    @Test
    void executionDomainsAreDedicatedTokens() {
        List<Token.Type> actual = new Lexer("gpu cpu quantum").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.GPU, Token.Type.CPU, Token.Type.QUANTUM,
                Token.Type.EOF), actual);
    }

    @Test
    void unsupportedDomainSyntaxAlwaysRejectsWithoutHostFallback() {
        List<String> sources = List.of(
                "pub gpu fnc kernel(): int { return 1; }",
                "pub fnc main(): int { gpu { return 1; } return 0; }",
                "pub fnc main(): int { cpu { return 1; } return 0; }",
                "pub fnc main(): int { gpu { cpu { return 1; } } return 0; }",
                "pub fnc main(): int { cpu { gpu { return 1; } } return 0; }",
                "pub fnc main(): int { gpu { cpu { gpu { return 1; } } } return 0; }",
                "pub fnc main(): int { const task = nb gpu { return 1; }; return 0; }",
                "pub fnc main(): int { const task = nb cpu { return 1; }; return 0; }",
                "pub fnc main(): int { return gpu; }",
                "pub fnc gpu(): int { return 1; }",
                "pub fnc main(): int { let gpu = 1; return 0; }",
                "pub quantum fnc kernel(): int { return 1; }"
        );
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("execution domain"), error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
        }
    }

    @Test
    void commentsStringsAndNonKeywordsStayOrdinarySource() {
        assertDoesNotThrow(() -> Parser.parse("""
                // gpu { cpu { quantum } }
                pub fnc compute_gpu(): int {
                    /* gpu { cpu {} } */
                    const message = "gpu cpu quantum";
                    return 42;
                }
                """));
    }
}
