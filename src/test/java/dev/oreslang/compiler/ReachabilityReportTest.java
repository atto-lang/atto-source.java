package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ReachabilityReportTest {
    @Test
    void emitsSchemaCompatibleSemanticReport() {
        String source = """
                pub fnc main(): int {
                  return live();
                }
                fnc live(): int {
                  return 42;
                }
                fnc dead(): int {
                  return 7;
                }
                """;
        BuildOptions options = BuildOptions.executable(Map.of());
        TreeShaker.Result result = OresCompiler.compileForBuild(source, options);

        String json = ReachabilityReport.render(
                result, options, "graal", "analysis",
                "graal-linux", "other");

        assertTrue(json.contains("\"schema_version\":\"1.0.0\""));
        assertTrue(json.contains("\"id\":\"__root__.main\""));
        assertTrue(json.contains("\"id\":\"__root__.live\""));
        assertTrue(json.contains("\"removed\":{\"total\":1}"));
        assertFalse(json.contains("\"id\":\"__root__.dead\""));
        assertTrue(result.removed("__root__.dead"));
    }

    @Test
    void libraryReportDoesNotPromotePrivateDependenciesToRoots() {
        String source = """
                pub fnc api(): int { return helper(); }
                fnc helper(): int { return 42; }
                """;
        BuildOptions options = BuildOptions.library(Map.of());
        TreeShaker.Result result = OresCompiler.compileForBuild(source, options);
        String json = ReachabilityReport.render(
                result, options, "graal", "analysis",
                "graal-linux", "library");

        int rootsStart = json.indexOf("\"roots\":[");
        int rootsEnd = json.indexOf("],\"retained\":", rootsStart);
        String roots = json.substring(rootsStart, rootsEnd);
        assertTrue(roots.contains("__root__.api"));
        assertFalse(roots.contains("__root__.helper"));
        assertTrue(result.retained("__root__.helper"));
        assertTrue(json.contains("\"reason\":\"public library API\""));
    }

    @Test
    void rejectsUnknownArtifactKinds() {
        BuildOptions options = BuildOptions.executable(Map.of());
        TreeShaker.Result result = OresCompiler.compileForBuild(
                "pub fnc main(): int { return 0; }", options);
        assertThrows(IllegalArgumentException.class, () ->
                ReachabilityReport.render(
                        result, options, "graal", "analysis",
                        "graal-linux", "mystery"));
    }
}
