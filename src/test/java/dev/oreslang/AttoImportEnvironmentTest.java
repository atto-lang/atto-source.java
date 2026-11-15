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
    void displaySpoofingAndMalformedUnicodeCannotSelectImportSources() {
        for (String dangerous : new String[] {
                String.valueOf((char) 0x202E), String.valueOf((char) 0x2028),
                String.valueOf((char) 0x2029), String.valueOf((char) 0x200F),
                String.valueOf((char) 0x061C), String.valueOf((char) 0x2066),
                String.valueOf((char) 0x2069), String.valueOf((char) 0xD800),
                String.valueOf((char) 0xDC00)
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImportPathInterpolation.expand("${repo}/helper",
                            Map.of("repo", "/source/" + dangerous)));
        }
        assertEquals("/source/café/helper",
                ImportPathInterpolation.expand("${repo}/helper",
                        Map.of("repo", "/source/café")));
    }

    @Test
    void realAttoSourceImportCompilesAndExecutesUsingExplicitRepoVariable() throws Exception {
        Path project = temp.resolve("project");
        Path source = project.resolve("src");
        Files.createDirectories(source);
        Path helper = source.resolve("helper.atto");
        Path main = source.resolve("main.atto");
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "."
                """);
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
    void canonicalRootAndRealEnvironmentVariableResolveIndependentlyInCompiledAttoGuest() throws Exception {
        Path project = temp.resolve("both-kinds");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "."
                """);
        Files.writeString(project.resolve("src/root_helper.atto"), """
                pub fnc root_message(): string { return "root-selected"; }
                """);
        Files.writeString(project.resolve("src/env_helper.atto"), """
                pub fnc env_message(): string { return "environment-selected"; }
                """);
        Path main = project.resolve("src/main.atto");
        Files.writeString(main, """
                import fnc root_message from "+/src/root_helper";
                import fnc env_message from "${repo}/src/env_helper";
                pub fnc main(): void {
                  stdio.println(root_message());
                  stdio.println(env_message());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(main,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of("repo", project.toString()), out,
                new ByteArrayOutputStream());
        assertEquals(3, result.units().size());
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("root-selected"));
        assertTrue(printed.contains("environment-selected"));
    }

    @Test
    void projectRootImportFollowsConfiguredManifestSubdirectory() throws Exception {
        Path project = temp.resolve("configured-root");
        Files.createDirectories(project.resolve("application/src"));
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "application"
                """);
        Path entry = project.resolve("application/src/main.atto");
        Path helper = project.resolve("application/src/helper.atto");
        Files.writeString(helper, "pub fnc answer(): int { return 42; }");
        var config = OresProjectConfig.discover(entry, Map.of());
        assertEquals(helper.toRealPath(), config.resolveImport(entry, "+/src/helper").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(entry, "+/../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(entry, "+src/helper"));
    }

    @Test
    void missingModernManifestNeverInfersCanonicalProjectRootFromCwd() throws Exception {
        Path project = temp.resolve("no-root-manifest");
        Files.createDirectories(project.resolve("src"));
        Path main = project.resolve("src/main.atto");
        Files.writeString(project.resolve("src/helper.atto"), "pub fnc x():int{return 1;}");
        var config = OresProjectConfig.discover(main, Map.of("repo", project.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(main, "+/src/helper"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(main, "${repo}/src/helper"));
    }

    @Test
    void nativeZedPackageManifestIsAnIndependentCanonicalProjectRoot() throws Exception {
        Path project = temp.resolve("zed-native-marker");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME), """
                [package]
                name = "consumer"
                """);
        Files.writeString(project.resolve("src/helper.atto"), """
                pub fnc result_from_root(): string { return "zed-package-root-verified"; }
                """);
        Path entry = project.resolve("src/main.atto");
        Files.writeString(entry, """
                import fnc result_from_root from "+/src/helper";
                pub fnc main(): void {
                  stdio.println(result_from_root());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("zed-package-root-verified"));
    }

    @Test
    void missingAndMalformedModernPackageIdentityNeverCreateRootAuthority() throws Exception {
        Path project = temp.resolve("zed-invalid-marker");
        Files.createDirectories(project);
        Path marker = project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME);
        Path entry = project.resolve("main.atto");
        for (String invalid : new String[] {
                "[package]\\n", "[package]\\nname = \"../escape\"\\n",
                "[package]\\nname = \"\"\\n",
                "[package]\\nname = \"BadUppercase\"\\n"
        }) {
            Files.writeString(marker, invalid.replace("\\n", "\n"));
            assertThrows(IllegalArgumentException.class,
                    () -> OresProjectConfig.discover(entry, Map.of()), invalid);
        }
    }

    @Test
    void modernManifestMarkerTakesPrecedenceAndInvalidMarkerCannotFallbackToLegacy() throws Exception {
        Path project = temp.resolve("dual-manifest");
        Files.createDirectories(project);
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), "schema_version = \"1\"\n");
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME),
                "schema_version = \"2\"\n");
        Path entry = project.resolve("main.atto");
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(entry, Map.of()));
        Files.delete(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME));
        Files.createDirectory(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME));
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(entry, Map.of()));
    }

    @Test
    void packageBoundariesCannotBeCrossedByEnvironmentOrRootImports() throws Exception {
        Path project = temp.resolve("package-boundary");
        Path nested = project.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(nested.resolve(OresProjectConfig.ATTO_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(nested.resolve("secret.atto"), "pub fnc secret(): int { return 7; }");
        Path main = project.resolve("main.atto");
        var config = OresProjectConfig.discover(main, Map.of("repo", project.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(main, "+/nested/secret"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(main, "${repo}/nested/secret"));
    }

    @Test
    void explicitProjectImportPathsCanAuthorizeExternalEnvRootButUnknownRootsCannot() throws Exception {
        Path project = temp.resolve("import-policy");
        Path external = temp.resolve("external-allowed");
        Path outside = temp.resolve("outside-denied");
        Files.createDirectories(project);
        Files.createDirectories(external);
        Files.createDirectories(outside);
        Files.writeString(external.resolve("lib.atto"), "pub fnc answer(): int { return 42; }");
        Files.writeString(outside.resolve("lib.atto"), "pub fnc answer(): int { return 7; }");
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME),
                "schema_version = \"1\"\n[source]\nimport_paths = [\""
                + external.toString().replace('\\', '/') + "\"]\n");
        Path main = project.resolve("main.atto");
        var config = OresProjectConfig.discover(main,
                Map.of("ALLOWED_ROOT", external.toString(), "OTHER_ROOT", outside.toString()));
        assertEquals(external.resolve("lib.atto").toRealPath(),
                config.resolveImport(main, "${ALLOWED_ROOT}/lib").orElseThrow().toRealPath());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(main, "${OTHER_ROOT}/lib"));
    }

    @Test
    void canonicalRootImportCannotShadowModernAttoWithSymlinkedSource() throws Exception {
        Path project = temp.resolve("symlink-root");
        Files.createDirectories(project);
        Files.writeString(project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Path source = project.resolve("real.atto");
        Path alias = project.resolve("alias.atto");
        Files.writeString(source, "pub fnc answer(): int { return 42; }");
        try {
            Files.createSymbolicLink(alias, source);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink creation unavailable");
        }
        var config = OresProjectConfig.discover(project.resolve("main.atto"), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "+/alias"));
    }

    @Test
    void unsupportedRemoteImportsAreRejectedBeforeAnyFetch() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        var config = OresProjectConfig.discover(project, Map.of(
                "REMOTE", "https://example.test/src",
                "FILE_URI", "file:///tmp/untrusted",
                "JAVA_URI", "java:java.lang.Runtime"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "${REMOTE}/foo"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "https://example.test/foo"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "${FILE_URI}/foo"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "${JAVA_URI}"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.atto"), "file:///tmp/untrusted"));
    }
}
