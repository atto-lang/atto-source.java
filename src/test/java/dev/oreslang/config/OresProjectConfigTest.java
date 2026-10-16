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

}
