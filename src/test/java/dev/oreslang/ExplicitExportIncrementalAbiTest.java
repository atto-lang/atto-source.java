package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportIncrementalAbiTest {

    @Test
    void changingOnlyExternalExportAliasInvalidatesUnchangedImporter() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        String importer = """
                import * as dep from "./lib.ores";

                pub routine main(): void {
                  return;
                }
                """;

        Map<String, String> firstSources = new LinkedHashMap<>();
        firstSources.put("lib.ores", """
                export fnc hidden_value as value;

                fnc hidden_value(): int {
                  return 1;
                }
                """);
        firstSources.put("main.ores", importer);

        IncrementalCompiler.BuildResult first = compiler.compile(firstSources);
        assertTrue(first.rebuilt("lib.ores"));
        assertTrue(first.rebuilt("main.ores"));

        Map<String, String> renamedSources = new LinkedHashMap<>();
        renamedSources.put("lib.ores", """
                export fnc hidden_value as renamed_value;

                fnc hidden_value(): int {
                  return 1;
                }
                """);
        renamedSources.put("main.ores", importer);

        IncrementalCompiler.BuildResult second = compiler.compile(renamedSources);
        assertTrue(second.rebuilt("lib.ores"),
                "the source unit changed");
        assertTrue(second.rebuilt("main.ores"),
                "external export alias changes are source ABI changes and must invalidate dependents");
    }
}
