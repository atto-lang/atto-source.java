package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

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
    void sharedTenCaseCpuGpuNbCorpusFailsClosed() throws IOException {
        // The JSON is shared byte-for-byte with the other four source repos.
        // Keep this explicit input registry readable without a new JSON library.
        var cases = Map.ofEntries(
                Map.entry("cpu-value", "cpu { 42 }"),
                Map.entry("gpu-value", "gpu { 42 }"),
                Map.entry("nb-cpu-future", "nb cpu { 42 }"),
                Map.entry("nb-gpu-future", "nb gpu { 42 }"),
                Map.entry("cpu-gpu-nested", "cpu { gpu { 42 } }"),
                Map.entry("gpu-cpu-nested", "gpu { cpu { 42 } }"),
                Map.entry("gpu-cpu-gpu-nested", "gpu { cpu { gpu { 42 } } }"),
                Map.entry("nb-mixed-nested", "nb cpu { nb gpu { 42 } }"),
                Map.entry("empty-gpu-value", "gpu {}"),
                Map.entry("empty-nb-gpu-value", "nb gpu {}")
        );
        String manifest = Files.readString(
                Path.of("contracts/v1/cpu-gpu-nb-unsupported-v1.json"));
        assertEquals(10, manifest.lines()
                .filter(line -> line.stripLeading().startsWith("\"")).count(),
                "shared negative registry may not silently omit cases");
        for (var item : cases.entrySet()) {
            String source = "pub fnc main():int{return " + item.getValue() + ";}";
            assertTrue(manifest.contains("\"" + item.getKey() + "\": \"" + source + "\""),
                    "shared negative source drift: " + item.getKey());
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source),
                    item.getKey());
            assertTrue(error.getMessage().contains("execution domain"),
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
