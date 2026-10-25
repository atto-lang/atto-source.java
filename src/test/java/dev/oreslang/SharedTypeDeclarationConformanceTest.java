package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Shared with native/formatter via byte-identical contracts/v1 TSV, not source execution claims. */
final class SharedTypeDeclarationConformanceTest {
    private record Vector(String id, String outcome, String group, String diagnostic, String source) {}

    private static List<Vector> vectors() throws Exception {
        Path path = Path.of("contracts/v1/type-declarations-conformance-v1.tsv");
        List<Vector> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\\t", -1);
            assertEquals(5, parts.length, "malformed shared vector: " + line);
            assertTrue(ids.add(parts[0]), "duplicate conformance id " + parts[0]);
            assertTrue(parts[1].equals("+") || parts[1].equals("-"), parts[0]);
            assertFalse(parts[4].isEmpty(), "empty program: " + parts[0]);
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3],
                    parts[4].replace("\\n", "\n")));
        }
        assertEquals(22, result.size(), "shared contract must not silently lose cases");
        return result;
    }

    @Test
    void acceptsAndRejectsExactSharedSources() throws Exception {
        for (Vector v : vectors()) {
            if (v.outcome().equals("+")) {
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(v.source()), v.id());
            } else {
                // Java parser diagnostics are not required to have native C++ diagnostic spellings.
                assertThrows(IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheck(v.source()), v.id());
            }
        }
    }

    @Test
    void bothIntroducersHaveIdenticalTypeDeclarationAst() throws Exception {
        Map<String, List<Ast.Program>> groups = new HashMap<>();
        for (Vector v : vectors()) {
            if (v.outcome().equals("+") && !v.group().equals("-")) {
                groups.computeIfAbsent(v.group(), ignored -> new ArrayList<>())
                        .add(OresCompiler.parseAndTypeCheck(v.source()));
            }
        }
        assertEquals(Set.of("basic", "public", "module", "kinds", "forward"), groups.keySet());
        for (var pair : groups.entrySet()) {
            assertEquals(2, pair.getValue().size(), "missing declare/define pair " + pair.getKey());
            var left = pair.getValue().get(0).modules().getFirst().declarations();
            var right = pair.getValue().get(1).modules().getFirst().declarations();
            int count = pair.getKey().equals("kinds") ? 2 : 1;
            assertEquals(left.subList(0, count), right.subList(0, count),
                    "type-only AST changed with introducer " + pair.getKey());
        }
    }
}
