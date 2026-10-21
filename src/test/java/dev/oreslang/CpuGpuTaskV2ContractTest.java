package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CpuGpuTaskV2ContractTest {
    @Test void taskIsContextualRatherThanGloballyReserved() {
        var tokens = new Lexer("task task_name").scan();
        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals("task", tokens.get(0).lexeme());
        assertEquals(Token.Type.IDENT, tokens.get(1).type());
        assertDoesNotThrow(() -> Parser.parse("pub fnc task(): int { return 42; }"));
    }

    @Test void canonicalTaskBlocksFailClosedUntilTypedLoweringIsEnabled() {
        for (String source : List.of(
                "pub fnc main():int{let Future<int> f = task { 42 }; return await f;}",
                "pub fnc main():int{let Future<int> f = cpu task { 42 }; return await f;}",
                "pub fnc main():int{let Future<int> f = gpu task { 42 }; return await f;}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(error.getMessage().contains("execution"), error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
        }
    }

    @Test void legacyNbTaskBlockGetsMigrationDiagnostic() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main():int{let Future<int> f = nb { 42 }; return await f;}"));
        assertTrue(error.getMessage().contains("canonical spelling is 'task { ... }'"), error::getMessage);
    }

    @Test void pinnedV2CorpusIsExactAndEveryCaseFailsClosed() throws Exception {
        String text = Files.readString(Path.of("contracts/v2/cpu-gpu-task-unsupported-v2.json"));
        var cases = Map.ofEntries(
                Map.entry("cpu-sync-value", "pub fnc main():int{return cpu { 42 };}"),
                Map.entry("gpu-sync-value", "pub fnc main():int{return gpu { 42 };}"),
                Map.entry("task-default-future", "pub fnc main():int{let Future<int> f = task { 42 }; return await f;}"),
                Map.entry("cpu-task-future", "pub fnc main():int{let Future<int> f = cpu task { 42 }; return await f;}"),
                Map.entry("gpu-task-future", "pub fnc main():int{let Future<int> f = gpu task { 42 }; return await f;}"),
                Map.entry("legacy-nb-default", "pub fnc main():int{let Future<int> f = nb { 42 }; return await f;}"),
                Map.entry("legacy-nb-cpu", "pub fnc main():int{let Future<int> f = nb cpu { 42 }; return await f;}"),
                Map.entry("legacy-nb-gpu", "pub fnc main():int{let Future<int> f = nb gpu { 42 }; return await f;}"),
                Map.entry("gpu-cpu-gpu-nested", "pub fnc main():int{return gpu { cpu { gpu { 42 } } };}"),
                Map.entry("empty-gpu-task-reject", "pub fnc main():void{gpu task {};}")
        );
        assertEquals(10, cases.size());
        for (var entry : cases.entrySet()) {
            String jsonLine = "\"" + entry.getKey() + "\": \"" + entry.getValue() + "\"";
            assertTrue(text.contains(jsonLine), "source drift: " + entry.getKey());
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(entry.getValue()), entry.getKey());
            assertTrue(error.getMessage().contains("execution"), error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
        }
    }
}
