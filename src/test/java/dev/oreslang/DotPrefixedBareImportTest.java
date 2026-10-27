package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.imports.ImportRules;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DotPrefixedBareImportTest {
    @Test
    void explicitRelativeClassifierRejectsMerelyDotPrefixedBareNames() {
        assertTrue(ImportRules.isExplicitRelativePath("."));
        assertTrue(ImportRules.isExplicitRelativePath(".."));
        assertTrue(ImportRules.isExplicitRelativePath("./child"));
        assertTrue(ImportRules.isExplicitRelativePath("../child"));
        assertTrue(ImportRules.isExplicitRelativePath(".\\child"));
        assertTrue(ImportRules.isExplicitRelativePath("..\\child"));

        assertFalse(ImportRules.isExplicitRelativePath(".hidden/util"));
        assertFalse(ImportRules.isExplicitRelativePath("..backup/util"));
        assertFalse(ImportRules.isExplicitRelativePath(".hidden\\util"));
        assertFalse(ImportRules.isExplicitRelativePath("..backup\\util"));
    }

    @Test
    void incrementalCompilerResolvesDotHiddenNameAsBareUnit() {
        IncrementalCompiler.BuildResult build = new IncrementalCompiler().compile(Map.of(
                "pkg/main.ores",
                """
                import fnc util from ".hidden/util";
                pub routine main(): void { return; }
                """,
                ".hidden/util.ores",
                """
                pub fnc util(): int { return 42; }
                """));

        assertEquals(2, build.units().size());
        assertTrue(build.units().containsKey("pkg/main.ores"));
        assertTrue(build.units().containsKey(".hidden/util.ores"));
    }

    @Test
    void incrementalCompilerResolvesDotDotPrefixedNameAsBareUnit() {
        IncrementalCompiler.BuildResult build = new IncrementalCompiler().compile(Map.of(
                "pkg/main.ores",
                """
                import fnc util from "..backup/util";
                pub routine main(): void { return; }
                """,
                "..backup/util.ores",
                """
                pub fnc util(): int { return 42; }
                """));

        assertEquals(2, build.units().size());
        assertTrue(build.units().containsKey("pkg/main.ores"));
        assertTrue(build.units().containsKey("..backup/util.ores"));
    }
}
