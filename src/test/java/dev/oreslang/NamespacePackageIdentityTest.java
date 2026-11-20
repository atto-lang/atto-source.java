package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source namespace labels are NOT implicit package ownership or namespace
 * federation. Multi-file ownership requires a verified manifest package ID.
 */
final class NamespacePackageIdentityTest {
    @Test
    void identicalNamespaceHeadersInDifferentFilesDoNotJoinPackages() {
        var build = new IncrementalCompiler().compile(Map.of(
                "dep-a/src/api.ores", """
                        namespace billing;
                        pub fnc charge(): int { return 1; }
                        """,
                "dep-b/src/api.ores", """
                        namespace billing;
                        pub fnc charge(): int { return 2; }
                        """));

        var a = build.units().get("dep-a/src/api.ores");
        var b = build.units().get("dep-b/src/api.ores");
        assertEquals("billing", a.namespace());
        assertEquals("billing", b.namespace());
        assertNotEquals(a.packageId(), b.packageId());
        assertEquals("file:dep-a/src/api.ores", a.packageId());
        assertEquals("file:dep-b/src/api.ores", b.packageId());
    }

    @Test
    void changingOnlySourceNamespaceDoesNotChangeSingletonPackageOwnership() {
        var compiler = new IncrementalCompiler();
        var first = compiler.compile(Map.of("src/service.ores", """
                namespace alpha;
                pub fnc get(): int { return 1; }
                """));
        var second = compiler.compile(Map.of("src/service.ores", """
                namespace beta;
                pub fnc get(): int { return 1; }
                """));

        assertEquals(first.units().get("src/service.ores").packageId(),
                second.units().get("src/service.ores").packageId());
        assertNotEquals(first.units().get("src/service.ores").namespace(),
                second.units().get("src/service.ores").namespace());
        assertTrue(second.rebuilt("src/service.ores"));
    }

    @Test
    void tenFilesAndThreeSourceNamespacesRemainSeparateSingletonPackagesWithoutManifestGrouping() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            String ns = i < 4 ? "billing" : i < 7 ? "identity" : "telemetry";
            sources.put("src/unit-" + i + ".ores",
                    "namespace " + ns + ";\n"
                            + "pub fnc unit_" + i + "(): int { return " + i + "; }\n");
        }
        var build = new IncrementalCompiler().compile(sources);
        Set<String> identities = build.units().values().stream()
                .map(IncrementalCompiler.CompiledUnit::packageId)
                .collect(Collectors.toSet());

        assertEquals(10, build.units().size());
        assertEquals(10, identities.size());
        assertEquals(3, build.units().values().stream()
                .map(IncrementalCompiler.CompiledUnit::namespace)
                .distinct().count());
    }
}
