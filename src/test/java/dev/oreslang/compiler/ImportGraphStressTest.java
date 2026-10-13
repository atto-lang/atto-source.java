package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ImportGraphStressTest {
    private static String id(int value) {
        return "unit-" + String.format(java.util.Locale.ROOT, "%05d", value) + ".ores";
    }

    @Test
    void tenThousandUnitDependencyChainHasNoRecursiveStackOverflow() {
        int count = 10_000;
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        // Reversed insertion order must not affect initialization ordering.
        for (int i = count - 1; i >= 0; i--) {
            graph.put(id(i), i == 0 ? Set.of() : Set.of(id(i - 1)));
        }
        List<List<String>> groups = ImportGraph.initializationGroups(graph);
        assertEquals(count, groups.size());
        assertEquals(List.of(id(0)), groups.getFirst());
        assertEquals(List.of(id(count - 1)), groups.getLast());
    }

    @Test
    void tenThousandUnitRingFormsOneStableStronglyConnectedComponent() {
        int count = 10_000;
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (int i = count - 1; i >= 0; i--) {
            graph.put(id(i), Set.of(id((i + 1) % count)));
        }
        List<List<String>> groups = ImportGraph.initializationGroups(graph);
        assertEquals(1, groups.size());
        assertEquals(count, groups.getFirst().size());
        assertEquals(id(0), groups.getFirst().getFirst());
        assertEquals(id(count - 1), groups.getFirst().getLast());
    }

    @Test
    void dependencyFirstOrderingAndComponentsIgnoreSourceMapInsertionOrder() {
        Map<String, Set<String>> forward = new LinkedHashMap<>();
        forward.put("a", Set.of("b", "c"));
        forward.put("b", Set.of("d"));
        forward.put("c", Set.of("d"));
        forward.put("d", Set.of());

        Map<String, Set<String>> reversed = new LinkedHashMap<>();
        reversed.put("d", Set.of());
        reversed.put("c", Set.of("d"));
        reversed.put("b", Set.of("d"));
        reversed.put("a", Set.of("c", "b"));

        List<List<String>> expected = List.of(
                List.of("d"), List.of("b"), List.of("c"), List.of("a"));
        assertEquals(expected, ImportGraph.initializationGroups(forward));
        assertEquals(expected, ImportGraph.initializationGroups(reversed));

        Map<String, Set<String>> mixed = new LinkedHashMap<>();
        mixed.put("d", Set.of("b"));
        mixed.put("c", Set.of("d"));
        mixed.put("b", Set.of("c"));
        mixed.put("a", Set.of("b"));
        List<List<String>> groups = ImportGraph.initializationGroups(mixed);
        assertEquals(2, groups.size());
        assertEquals(List.of("b", "c", "d"), groups.getFirst());
        assertTrue(groups.getLast().contains("a"));
    }
}
