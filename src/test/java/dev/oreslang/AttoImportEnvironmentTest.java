package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.imports.ImportPathInterpolation;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttoImportEnvironmentTest {
    @TempDir Path temp;

    @Test
    void compilerInterpretsOnlyExplicitBracedVariables() {
        var environment = Map.of("repo", "/srv/my-project", "LIB", "src", "FILE", "config");
        assertEquals("/srv/my-project/src/config",
                ImportPathInterpolation.expand("${repo}/${LIB}/${FILE}", environment));
        assertEquals("$repo/src/config",
                ImportPathInterpolation.expand("$repo/src/config", environment));
        assertEquals("+/src/config",
                ImportPathInterpolation.expand("+/src/config", environment));
        assertEquals("+/src/config",
                ImportPathInterpolation.expand("+/${LIB}/config", environment));
        assertEquals("/srv/my-project/src/config",
                ImportPathInterpolation.expand("${repo}/src/config", environment));
    }

    @Test
    void malformedOrMissingVariablesNeverFallBackToAmbientEnvironment() {
        Map<String, String> supplied = Map.of("repo", "/source");
        for (String raw : new String[] {
                "${HOME}/foo", "${}/foo", "${0BAD}/foo", "${bad-name}/foo",
                "${repo", "${repo:-default}/foo", "${repo.foo}/foo"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImportPathInterpolation.expand(raw, supplied), raw);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ImportPathInterpolation.expand("${EMPTY}/foo", Map.of("EMPTY", "")));
    }

    @Test
    void rejectsNestedExpansionControlBytesAndOverlargeValues() {
        for (String value : new String[] {
                "${OTHER}", "line\nfeed", "tab\tpath", String.valueOf((char) 0),
                "x".repeat(4097), "é".repeat(2049)
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImportPathInterpolation.expand("${repo}/foo", Map.of("repo", value)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ImportPathInterpolation.expand("x".repeat(4097), Map.of()));
    }

    @Test
    void realAttoSourceImportCompilesAndExecutesUsingExplicitRepoVariable() throws Exception {
        Path project = temp.resolve("project");
        Path source = project.resolve("src");
        Files.createDirectories(source);
        Path helper = source.resolve("helper.atto");
        Path main = source.resolve("main.atto");
        Files.writeString(helper, """
                pub fnc imported_message(): string {
                  return "braced-env-import-ran";
                }
                """);
        Files.writeString(main, """
                import fnc imported_message from "${repo}/src/helper";
                pub fnc main(): void {
                  stdio.println(imported_message());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of("repo", project.toString()), out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("braced-env-import-ran"));
    }

    @Test
    void canonicalAttoPathsOverridesLegacySearchRootsWithoutMerging() throws Exception {
        Path root = temp.resolve("consumer");
        Path canonical = temp.resolve("canonical");
        Path legacy = temp.resolve("legacy");
        Files.createDirectories(root);
        Files.createDirectories(canonical);
        Files.createDirectories(legacy);
        Files.writeString(canonical.resolve("module.atto"), "pub fnc marker(): int { return 7; }");
        Files.writeString(legacy.resolve("module.atto"), "pub fnc marker(): int { return 8; }");

        Path importer = root.resolve("main.atto");
        var config = OresProjectConfig.discover(importer, Map.of(
                OresProjectConfig.ENV_ATTO_PATHS, canonical.toString(),
                OresProjectConfig.ENV_ORES_PATHS, legacy.toString(),
                OresProjectConfig.ENV_ORESLANG_PATH, legacy.toString()));
        assertEquals(canonical.resolve("module.atto"), config.resolveImport(importer, "module").orElseThrow());
        assertFalse(config.searchRoots().contains(legacy));

        var empty = OresProjectConfig.discover(importer, Map.of(
                OresProjectConfig.ENV_ATTO_PATHS, "",
                OresProjectConfig.ENV_ORES_PATHS, legacy.toString()));
        assertTrue(empty.resolveImport(importer, "module").isEmpty(),
                "an explicitly empty ATTO_PATHS must not fall through to legacy ambient paths");
        var ores = OresProjectConfig.discover(importer, Map.of(
                OresProjectConfig.ENV_ORES_PATHS, legacy.toString()));
        assertEquals(legacy.resolve("module.atto"), ores.resolveImport(importer, "module").orElseThrow());
    }

    @Test
    void unsupportedRemoteImportsAreRejectedBeforeAnyFetch() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        var config = OresProjectConfig.discover(project, Map.of("REMOTE", "https://example.test/src"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "${REMOTE}/foo"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "https://example.test/foo"));
    }
}
