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
    @Test
    void changingExplicitlyExportedConstantInvalidatesImporterWithoutMetadataEncoding() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String importer = """
                import * as dep from "./lib.ores";
                pub routine main(): void { return; }
                """;

        Map<String, String> first = new LinkedHashMap<>();
        first.put("lib.ores", """
                export const answer;
                const int answer = 41;
                """);
        first.put("main.ores", importer);
        assertTrue(compiler.compile(first).rebuilt("main.ores"));

        Map<String, String> second = new LinkedHashMap<>();
        second.put("lib.ores", """
                export const answer;
                const int answer = 42;
                """);
        second.put("main.ores", importer);
        IncrementalCompiler.BuildResult result = compiler.compile(second);
        assertTrue(result.rebuilt("lib.ores"));
        assertTrue(result.rebuilt("main.ores"),
                "constant value changes must conservatively invalidate dependents");
    }

    @Test
    void unchangedImplicitPublicConstantDoesNotPreventIncrementalReuse() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.ores", "pub const int answer = 42;");
        sources.put("main.ores", """
                import * as dep from "./lib.ores";
                pub routine main(): void { return; }
                """);

        assertTrue(compiler.compile(sources).rebuilt("main.ores"));
        IncrementalCompiler.BuildResult cached = compiler.compile(sources);
        assertTrue(cached.reusedUnits().contains("lib.ores"));
        assertTrue(cached.reusedUnits().contains("main.ores"));
    }


    @Test
    void exportedClassWithPublicConstMemberUsesConservativeIncrementalFingerprint() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.ores", """
                export class Box;

                define class Box as
                  pub const int code = 7;
                end
                """);
        sources.put("main.ores", """
                import * as dep from "./lib.ores";
                pub routine main(): void { return; }
                """);

        IncrementalCompiler.BuildResult first = compiler.compile(sources);
        assertTrue(first.rebuilt("lib.ores"));
        assertTrue(first.rebuilt("main.ores"));

        IncrementalCompiler.BuildResult cached = compiler.compile(sources);
        assertTrue(cached.reused("lib.ores"));
        assertTrue(cached.reused("main.ores"));
    }

}
