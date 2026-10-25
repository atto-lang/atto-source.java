package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test void canonicalAndLegacyCpuTaskSpellingsNormalizeToOneAst() {
        var canonical = taskInitializer(
                "pub fnc main():void{let Future<int> f = cpu task { return 42; }; return;}");
        assertEquals(Ast.ExecutionDomain.CPU, canonical.domain());
        assertFalse(canonical.legacyNb());

        var defaultCpu = taskInitializer(
                "pub fnc main():void{let Future<int> f = task { return 42; }; return;}");
        assertEquals(Ast.ExecutionDomain.CPU, defaultCpu.domain());
        assertFalse(defaultCpu.legacyNb());

        var legacy = taskInitializer(
                "pub fnc main():void{let Future<int> f = nb cpu { return 42; }; return;}");
        assertEquals(Ast.ExecutionDomain.CPU, legacy.domain());
        assertTrue(legacy.legacyNb());
    }

    @Test void cpuTaskTypeChecksAsFutureAndAwaitUnwrapsIt() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> t = cpu task {
                    return 42;
                  };
                  val int answer = await t;
                  return;
                }
                """));
    }

    @Test void valueProducingTaskRequiresContextualFutureType() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                          val t = cpu task {
                            return 42;
                          };
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("contextual Future<T>"), error::getMessage);
    }

    @Test void gpuTaskNeverFallsBackToCpu() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                          let Future<int> t = gpu task {
                            return 42;
                          };
                          val int answer = await t;
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("physical GPU dispatch"), error::getMessage);
        assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
    }

    @Test void bareSynchronousCpuAndGpuRegionsRemainReservedUntilContinuationLoweringLands() {
        for (String domain : new String[]{"cpu", "gpu"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main():int{return " + domain + " { 42 };}"));
            assertTrue(error.getMessage().contains("synchronous execution domain"), error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
        }
    }

    @Test void pinnedV2CorpusDoesNotDriftWhileBackendsAdvanceAtDifferentRates() throws Exception {
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
            assertTrue(
                    text.contains("\"" + entry.getKey() + "\": \"" + entry.getValue() + "\""),
                    "source drift: " + entry.getKey());
        }
    }

    private static Ast.TaskExpr taskInitializer(String source) {
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) fn.body().getFirst();
        return assertInstanceOf(Ast.TaskExpr.class, binding.initializer());
    }
}
