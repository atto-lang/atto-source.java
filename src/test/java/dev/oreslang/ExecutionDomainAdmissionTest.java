package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
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
                "pub fnc main(): int { const x = gpu {}; return 0; }",
                "pub fnc main(): int { const x = cpu {}; return 0; }",
                "pub fnc main(): int { const x = nb gpu {}; return 0; }",
                "pub fnc main(): int { const x = nb cpu {}; return 0; }",
                "pub fnc main(): int { const x = nb gpu { const y = nb cpu {}; }; return 0; }",
                "pub fnc main(): int { gpu { cpu { return 1; } } return 0; }",
                "pub fnc main(): int { cpu { gpu { return 1; } } return 0; }",
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
    void canonicalAndLegacyTaskDomainBlocksRemainParserVisible() {
        // Parse admission alone does not authorize a GPU device launch.
        // The GPU task remains rejected by the type checker on this backend.
        List<String> sources = List.of(
                "pub fnc main(): int { let Future<int> f = cpu task { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = gpu task { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = nb cpu { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = nb gpu { return 42; }; return 0; }"
        );
        for (String source : sources) {
            assertDoesNotThrow(() -> Parser.parse(source), source);
        }
    }

    @Test
    void gpuTasksNeverReachHostTypeChecking() {
        // A parser-visible Task is not permission to dispatch a GPU kernel.
        // Both source spellings must be rejected *before* host execution.
        for (String spelling : List.of(
                "gpu task { return 42; }", "nb gpu { return 42; }")) {
            String source = "pub fnc main(): int { let Future<int> pending = "
                    + spelling + "; return 0; }";
            var program = assertDoesNotThrow(() -> Parser.parse(source));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(program), source);
            assertTrue(error.getMessage().contains("physical GPU dispatch"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
        }
    }

    @Test
    void nestedSynchronousRegionsStillFailClosedInsideTaskBlocks() {
        List<String> sources = List.of(
                "pub fnc main(): int { cpu {} return 0; }",
                "pub fnc main(): int { gpu {} return 0; }",
                "pub fnc main(): int { let Future<int> f = cpu task { gpu { return 1; } }; return 0; }",
                "pub fnc main(): int { let Future<int> f = gpu task { cpu { return 1; } }; return 0; }",
                "pub fnc main(): int { let Future<int> f = nb gpu { cpu { return 1; } }; return 0; }"
        );
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("synchronous execution domain"),
                    error::getMessage);
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
