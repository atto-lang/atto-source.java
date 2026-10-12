package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MacroIncrementalCompilerTest {
    private static String unit(String mode, int amount) {
        return """
                @expand(%s)
                macro_rules! shift {
                  ($value:expr) => { ($value + %d) };
                }
                define module app as
                  pub fnc run(): int {
                    return shift!(41);
                  }
                end
                """.formatted(mode, amount);
    }

    @Test
    void incrementalCompilerFencesAotAndRetainsLastGoodGenerationOnFailure() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(Map.of("app.ores", unit("aot", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(Map.of("app.ores", unit("aot", 2))));
        assertDoesNotThrow(() -> compiler.compile(Map.of("app.ores", unit("aot", 1))));
        compiler.clear();
        assertDoesNotThrow(() -> compiler.compile(Map.of("app.ores", unit("aot", 2))));
    }

    @Test
    void incrementalCompilerRecompilesRebuildableMacrosOnlyAsNewGeneration() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(Map.of("app.ores", unit("rebuildable", 1))));
        assertDoesNotThrow(() -> compiler.compile(Map.of("app.ores", unit("rebuildable", 2))));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(Map.of("app.ores", unit("aot", 3))));
    }

    @Test
    void rejectsJitMacroModeAtCompilationBeforeCodeGeneration() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(Map.of("app.ores", unit("jit", 1))));
        assertTrue(rejected.getMessage().contains("forbidden"), rejected.getMessage());
    }
}
