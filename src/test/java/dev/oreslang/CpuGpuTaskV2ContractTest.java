package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test void pinnedV2CorpusIsPresentAndComplete() throws Exception {
        String text = Files.readString(Path.of("contracts/v2/cpu-gpu-task-unsupported-v2.json"));
        for (String key : List.of(
                "cpu-sync-value", "gpu-sync-value", "task-default-future",
                "cpu-task-future", "gpu-task-future", "legacy-nb-default",
                "legacy-nb-cpu", "legacy-nb-gpu", "gpu-cpu-gpu-nested",
                "empty-gpu-task-reject")) {
            assertTrue(text.contains("\"" + key + "\""), key);
        }
    }
}
