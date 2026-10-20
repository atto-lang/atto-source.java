package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SourceClosureFingerprintTest {
    private static String digest(Map<String, String> units,
                                 Map<String, Map<String, String>> resolutions,
                                 String manifest,
                                 List<String> roots) {
        return SourceClosureFingerprint.sha256(
                units, resolutions, "/project/.oreslangc.cfg.toml",
                manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8), roots);
    }

    @Test
    void stableAcrossInsertionOrderButSensitiveToTransitiveSource() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("/project/main.ores", "import fnc f from './dep';");
        sources.put("/project/dep.ores", "pub fnc f(): int { return 1; }");
        var imports = Map.of("/project/main.ores",
                Map.of("./dep", "/project/dep.ores"));
        String original = digest(sources, imports, "schema_version = \"1\"",
                List.of("/project"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("/project/dep.ores", sources.get("/project/dep.ores"));
        reversed.put("/project/main.ores", sources.get("/project/main.ores"));
        assertEquals(original, digest(reversed, imports, "schema_version = \"1\"",
                List.of("/project")));
        reversed.put("/project/dep.ores", "pub fnc f(): int { return 2; }");
        assertNotEquals(original, digest(reversed, imports, "schema_version = \"1\"",
                List.of("/project")));
    }

    @Test
    void manifestRootsAndImportDestinationsAffectDigest() {
        var units = Map.of("main", "main", "depA", "abc", "depB", "abc");
        var routeA = Map.of("main", Map.of("dep", "depA"));
        var routeB = Map.of("main", Map.of("dep", "depB"));
        String reference = digest(units, routeA, "manifestA", List.of("rootA"));
        assertNotEquals(reference, digest(units, routeB, "manifestA",
                List.of("rootA")));
        assertNotEquals(reference, digest(units, routeA, "manifestB",
                List.of("rootA")));
        assertNotEquals(reference, digest(units, routeA, "manifestA",
                List.of("rootB")));
    }

    @Test
    void lengthFramingSeparatesFields() {
        String a = digest(Map.of("name", "ab"), Map.of(), "", List.of("c"));
        String b = digest(Map.of("name", "a"), Map.of(), "", List.of("bc"));
        assertNotEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void refusesMissingResolvedUnitsAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> digest(
                Map.of("main", "text"),
                Map.of("main", Map.of("dep", "missing")),
                "", List.of(".")));
        assertThrows(IllegalArgumentException.class, () -> digest(
                Map.of("main", "text"), Map.of(), "manifest".repeat(200000),
                List.of(".")));
        assertThrows(IllegalArgumentException.class, () -> digest(
                Map.of(), Map.of(), "", List.of(".")));
    }
    @Test
    void transitiveImportsAffectReplayEvenWhenEntryAndDirectImportAreUnchanged() {
        // The entry source and first-level dependency remain byte-identical.
        // A change two import edges away MUST invalidate the replay digest.
        var sources = new LinkedHashMap<String, String>();
        sources.put("main", "import fnc a from './a';");
        sources.put("a", "import fnc b from './b'; pub fnc a(): int { return b(); }");
        sources.put("b", "pub fnc b(): int { return 1; }");
        sources.put("b2", "pub fnc b(): int { return 1; }");
        var edges = Map.of(
                "main", Map.of("./a", "a"),
                "a", Map.of("./b", "b", "./fallback", "b2"));
        String first = digest(sources, edges, "manifest", List.of("/project"));
        sources.put("b", "pub fnc b(): int { return 2; }");
        assertNotEquals(first, digest(sources, edges, "manifest", List.of("/project")));

        // A source with unchanged text but a rebound transitive import is
        // also a different program. Never identify imports by basename only.
        sources.put("b", "pub fnc b(): int { return 1; }");
        String alternate = digest(sources, Map.of(
                "main", Map.of("./a", "a"),
                "a", Map.of("./b", "b2", "./fallback", "b")),
                "manifest", List.of("/project"));
        assertNotEquals(first, alternate);
    }

}
