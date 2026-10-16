package dev.oreslang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class OresProjectConfigTest {
    @TempDir Path temp;

    @Test
    void discoversNearestManifestAndResolvesProjectPaths() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Path lib = project.resolve("lib");
        Files.createDirectories(src.resolve("nested"));
        Files.createDirectories(lib);
        Files.writeString(src.resolve("main.ores"), "pub fnc main() => void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"

                [project]
                name = "demo"
                version = "0.1.0"
                root = "."

                [source]
                roots = ["src"]
                import_paths = ["lib"]

                [entrypoints]
                main = "src/main.ores"
                """);

        OresProjectConfig config = OresProjectConfig.discover(
                src.resolve("nested").resolve("file.ores"),
                Map.of());

        assertEquals(project.toAbsolutePath().normalize(), config.projectRoot());
        assertEquals("demo", config.projectName().orElseThrow());
        assertEquals(src.toAbsolutePath().normalize(), config.sourceRoots().getFirst());
        assertEquals(lib.toAbsolutePath().normalize(), config.importPaths().getFirst());
        assertEquals(src.resolve("main.ores").toAbsolutePath().normalize(), config.mainEntrypoint().orElseThrow());
    }

    @Test
    void oreslangPathIsAnOrderedOsNativePathList() throws Exception {
        Path one = temp.resolve("one");
        Path two = temp.resolve("two");

        OresProjectConfig config = OresProjectConfig.discover(
                temp,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH,
                        one + File.pathSeparator + File.pathSeparator + two + File.pathSeparator));

        assertEquals(
                java.util.List.of(one.toAbsolutePath().normalize(), two.toAbsolutePath().normalize()),
                config.environmentPaths());
    }

    @Test
    void projectRootsWinBeforeOreslangPathFallback() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Path ambient = temp.resolve("ambient");
        Files.createDirectories(src.resolve("pkg"));
        Files.createDirectories(ambient.resolve("pkg"));
        Files.writeString(src.resolve("pkg/value.ores"), "pub fnc value() => int { return 1; }");
        Files.writeString(ambient.resolve("pkg/value.ores"), "pub fnc value() => int { return 2; }");
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["src"]
                """);

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, ambient.toString()));

        assertEquals(
                src.resolve("pkg/value.ores").toAbsolutePath().normalize(),
                config.resolveImport(importer, "pkg/value").orElseThrow());
    }

    @Test
    void relativeImportsNeverFallThroughToSearchRoots() throws Exception {
        Path importerDir = temp.resolve("src");
        Path ambient = temp.resolve("ambient");
        Files.createDirectories(importerDir);
        Files.createDirectories(ambient);
        Path importer = importerDir.resolve("main.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(ambient.resolve("missing.ores"), "pub fnc value() => int { return 1; }");

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, ambient.toString()));

        assertTrue(config.resolveImport(importer, "./missing").isEmpty());
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(manifest, "schema_version = \"999\"\n");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(error.getMessage().contains("schema_version"));
    }

    @Test
    void oresPathsIsColonSeparatedAndFollowsProjectRoots() throws Exception {
        Path local = temp.resolve("local");
        Path first = temp.resolve("one");
        Path second = temp.resolve("two");
        Files.createDirectories(local.resolve("src"));
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(local.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "app"
                [source]
                roots = ["src"]
                """);
        OresProjectConfig config = OresProjectConfig.discover(local.resolve("src/main.ores"),
                Map.of(OresProjectConfig.ENV_ORES_PATHS,
                        first + "::" + second + ":",
                        OresProjectConfig.ENV_ORESLANG_PATH, second.toString()));

        assertEquals(java.util.List.of(first, second), config.environmentPaths());
        assertEquals(java.util.List.of(local.resolve("src"), first, second), config.searchRoots());
    }

    @Test
    void rootPrefixResolvesFromPackageManifestRatherThanImporterFolder() throws Exception {
        Path project = temp.resolve("pkg-root");
        Files.createDirectories(project.resolve("src/nested"));
        Files.createDirectories(project.resolve("src/shared"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "package-demo"
                version = "1.0.0"
                [source]
                roots = ["src"]
                """);
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "legacy"
                """);
        Path helper = project.resolve("src/shared/util.ores");
        Files.writeString(helper, "pub fnc util() : int { return 1; }");
        OresProjectConfig config = OresProjectConfig.discover(project.resolve("src/nested/main.ores"), Map.of());

        assertEquals("package-demo", config.projectName().orElseThrow());
        assertEquals(helper.toRealPath(), config.resolveImport(project.resolve("src/nested/main.ores"),
                "@/src/shared/util").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("src/nested/main.ores"), "@/../escape"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("src/nested/main.ores"), "@/"));
    }

    @Test
    void symlinkedEnvironmentRootsAndInstalledZedPackagesResolveToPhysicalFiles() throws Exception {
        Path real = temp.resolve("real");
        Path project = temp.resolve("consumer");
        Files.createDirectories(real.resolve("src"));
        Files.createDirectories(project.resolve("zed_modules/acme"));
        Files.writeString(real.resolve("src/answer.ores"), "pub fnc answer() : int { return 42; }");
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "consumer"
                [dependencies]
                answer = { zed = "acme/math" }
                """);
        Path envLink = temp.resolve("env-link");
        Path packageLink = project.resolve("zed_modules/acme/math");
        try {
            Files.createSymbolicLink(envLink, real.resolve("src"));
            Files.createSymbolicLink(packageLink, real);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "filesystem symlinks unavailable");
        }

        OresProjectConfig config = OresProjectConfig.discover(project.resolve("main.ores"),
                Map.of(OresProjectConfig.ENV_ORES_PATHS, envLink.toString()));
        Path physical = real.resolve("src/answer.ores").toRealPath();
        assertEquals(physical, config.resolveImport(project.resolve("main.ores"),
                "answer").orElseThrow());
        assertEquals(physical, config.resolveImport(project.resolve("main.ores"),
                "pkg:answer/src/answer").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.ores"), "pkg:unknown/src/answer"));
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.ores"), "pkg:answer/../../escape"));
    }

    @Test
    void malformedPackageAliasesFailClosed() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, """
                schema_version = "1"
                [dependencies]
                unsafe = "../escape"
                """);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(invalid.getMessage().contains("invalid zed-pkg identifier"));
    }


    @Test
    void respectsZedInstallDirectoryFromZpkgManifest() throws Exception {
        Path project = temp.resolve("custom-zed-install");
        Path installed = project.resolve(".vendor/.zed/acme/math");
        Files.createDirectories(installed.resolve("lib"));
        Files.writeString(installed.resolve("lib/math.ores"), "pub fnc math() : int { return 1; }");
        Files.writeString(project.resolve(".zpkg.toml"), """
                [package]
                org = "acme"
                name = "consumer"
                version = "0.1.0"
                [install]
                dir = ".vendor/.zed"
                """);
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                math = "acme/math"
                """);
        OresProjectConfig config = OresProjectConfig.discover(project.resolve("src/main.ores"), Map.of());
        assertEquals(installed.resolve("lib/math.ores").toRealPath(),
                config.resolveImport(project.resolve("src/main.ores"), "pkg:math/lib/math").orElseThrow());
    }

    @Test
    void rejectsZedInstallDirectoryTraversal() throws Exception {
        Path project = temp.resolve("zed-traversal");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".zpkg.toml"), """
                [install]
                dir = "../outside"
                """);
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                math = "acme/math"
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(project.resolve("main.ores"), Map.of()));
    }


    @Test
    void verifiesInstalledPackageMetadataWhenPresent() throws Exception {
        Path project = temp.resolve("identity");
        Path installed = project.resolve("zed_modules/acme/tools");
        Files.createDirectories(installed.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                tools = { zed = "acme/tools" }
                """);
        Files.writeString(installed.resolve("src/hello.ores"),
                "pub fnc hello() : int { return 1; }");
        Path metadata = installed.resolve(".zpkg.toml");
        Files.writeString(metadata, """
                [package]
                org = "evil"
                name = "tools"
                version = "1.0.0"
                """);
        OresProjectConfig config = OresProjectConfig.discover(project.resolve("main.ores"), Map.of());
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.ores"), "pkg:tools/src/hello"));
        assertTrue(mismatch.getMessage().contains("identity mismatch"));

        Files.writeString(metadata, """
                [package]
                org = "acme"
                name = "tools"
                version = "1.0.0"
                """);
        assertEquals(installed.resolve("src/hello.ores").toRealPath(),
                config.resolveImport(project.resolve("main.ores"), "pkg:tools/src/hello").orElseThrow());
    }

    @Test
    void rejectsInvalidInstalledZedMetadataInsteadOfSilentlyProceeding() throws Exception {
        Path project = temp.resolve("bad-zpkg");
        Path installed = project.resolve("zed_modules/acme/tools");
        Files.createDirectories(installed.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                tools = "acme/tools"
                """);
        Files.writeString(installed.resolve(".zpkg.toml"), "[package\n");
        Files.writeString(installed.resolve("src/hello.ores"), "pub fnc hello(): int { return 1; }");
        OresProjectConfig config = OresProjectConfig.discover(project.resolve("main.ores"), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(project.resolve("main.ores"), "pkg:tools/src/hello"));
    }


    @Test
    void malformedDeclaredSourceAndDependencyTablesFailBeforeCompilation() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, """
                schema_version = "1"
                [source]
                roots = "src"
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));

        Files.writeString(manifest, """
                schema_version = "1"
                dependencies = ["acme/tools"]
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
    }

    @Test
    void nonStringInstallDirectoryIsNotSilentlyReplacedWithDefault() throws Exception {
        Path project = temp.resolve("malformed-install");
        Files.createDirectories(project);
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                tools = "acme/tools"
                """);
        Files.writeString(project.resolve(".zpkg.toml"), """
                [install]
                dir = 123
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(project.resolve("main.ores"), Map.of()));
    }


    @Test
    void parentPackageRootWinsOverNestedLegacyCompilerManifest() throws Exception {
        Path project = temp.resolve("package-legacy-nesting");
        Path nested = project.resolve("src/nested");
        Files.createDirectories(nested);
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "root"
                """);
        Files.writeString(nested.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "legacy-nested"
                """);
        Files.writeString(project.resolve("root.ores"), "pub fnc root(): int { return 1; }");
        OresProjectConfig config = OresProjectConfig.discover(nested.resolve("main.ores"), Map.of());
        assertEquals("root", config.projectName().orElseThrow());
        assertEquals(project.resolve("root.ores").toRealPath(),
                config.resolveImport(nested.resolve("main.ores"), "@/root").orElseThrow());
    }


    @Test
    void rootAndEntrypointWrongTomlTypesFailClosed() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, """
                schema_version = "1"
                [project]
                root = 123
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));

        Files.writeString(manifest, """
                schema_version = "1"
                [entrypoints]
                main = ["src/main.ores"]
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
    }


    @Test
    void malformedNestedManifestTablesUseStableIllegalArgumentErrors() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);

        Files.writeString(manifest, """
                schema_version = "1"
                source = 42
                """);
        IllegalArgumentException source = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(source.getMessage().contains("source.roots"), source.getMessage());

        Files.writeString(manifest, """
                schema_version = 42
                """);
        IllegalArgumentException schema = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(schema.getMessage().contains("schema_version"), schema.getMessage());

        Files.writeString(manifest, """
                schema_version = "1"
                entrypoints = ["src/main.ores"]
                """);
        IllegalArgumentException entrypoint = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        assertTrue(entrypoint.getMessage().contains("entrypoints.main"), entrypoint.getMessage());
    }


    @Test
    void explicitlyEmptySourceRootsDoNotSilentlyExposeProjectFiles() throws Exception {
        Path project = temp.resolve("no-implicit-roots");
        Files.createDirectories(project);
        Path importer = project.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }");
        Files.writeString(project.resolve("secret.ores"), "pub fnc secret(): int { return 7; }");
        Path manifest = project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);
        Files.writeString(manifest, """
                schema_version = "1"
                [source]
                roots = []
                """);
        OresProjectConfig restricted = OresProjectConfig.discover(importer, Map.of());
        assertTrue(restricted.sourceRoots().isEmpty());
        assertTrue(restricted.resolveImport(importer, "secret").isEmpty());
        assertEquals(project.resolve("secret.ores").toRealPath(),
                restricted.resolveImport(importer, "@/secret").orElseThrow());

        // Omitted roots preserve the legacy/default project-root lookup.
        Files.writeString(manifest, "schema_version = \"1\"\n");
        OresProjectConfig defaults = OresProjectConfig.discover(importer, Map.of());
        assertEquals(java.util.List.of(project.toAbsolutePath().normalize()), defaults.sourceRoots());
        assertEquals(project.resolve("secret.ores").toRealPath(),
                defaults.resolveImport(importer, "secret").orElseThrow());
    }


    @Test
    void dotPrefixedBareModulesUseConfiguredRootsOnlyExplicitDotSlashIsRelative() throws Exception {
        Path project = temp.resolve("dot-prefix-modules");
        Path src = project.resolve("src");
        Path importerDir = src.resolve("nested");
        Files.createDirectories(src.resolve(".hidden"));
        Files.createDirectories(src.resolve("..backup"));
        Files.createDirectories(importerDir.resolve(".hidden"));
        Path globalDot = Files.writeString(src.resolve(".hidden/util.ores"),
                "pub fnc util(): int { return 1; }");
        Path siblingDot = Files.writeString(importerDir.resolve(".hidden/util.ores"),
                "pub fnc util(): int { return 2; }");
        Path globalDots = Files.writeString(src.resolve("..backup/util.ores"),
                "pub fnc util(): int { return 3; }");
        Path importer = Files.writeString(importerDir.resolve("main.ores"),
                "pub fnc main(): void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["src"]
                """);
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(globalDot.toRealPath(),
                config.resolveImport(importer, ".hidden/util").orElseThrow());
        assertEquals(globalDots.toRealPath(),
                config.resolveImport(importer, "..backup/util").orElseThrow());
        assertEquals(siblingDot.toRealPath(),
                config.resolveImport(importer, "./.hidden/util").orElseThrow());
        assertTrue(config.resolveImport(importer, "../.hidden/missing").isEmpty());
    }

}
