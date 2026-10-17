package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImportResolutionInvalidationTest {

    private static String apiSource() {
        return """
                pub define contract ApiOne as
                  fnc one(): int;
                end
                pub define contract ApiTwo as
                  fnc two(): int;
                end
                """;
    }

    @Test
    void remappingImportPathsRebuildsImporterEvenWhenDependencySetIsUnchanged() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api-a.ores", apiSource());
        sources.put("api-b.ores", apiSource());
        sources.put("worker.ores", """
                import contract ApiOne as A from "logical-one";
                import contract ApiTwo as B from "logical-two";

                define module Worker conforms A, B as
                  pub fnc one(): int { return 1; }
                  pub fnc two(): int { return 2; }
                end
                """);
        sources.put("consumer.ores", """
                import module Worker from "./worker.ores";
                pub routine main(): void { return; }
                """);

        Map<String, Map<String, String>> firstResolution = Map.of(
                "worker.ores",
                Map.of("logical-one", "api-a.ores", "logical-two", "api-b.ores"));
        var first = compiler.compile(sources, firstResolution);
        assertTrue(first.rebuilt("worker.ores"));

        Map<String, Map<String, String>> swappedResolution = Map.of(
                "worker.ores",
                Map.of("logical-one", "api-b.ores", "logical-two", "api-a.ores"));
        var second = compiler.compile(sources, swappedResolution);

        assertTrue(second.rebuilt("worker.ores"),
                "linker mapping changed even though the resolved dependency set stayed {api-a, api-b}");
        assertTrue(second.rebuilt("consumer.ores"),
                "a linked ABI identity change must invalidate the importer's reverse-dependency closure");
    }
}
