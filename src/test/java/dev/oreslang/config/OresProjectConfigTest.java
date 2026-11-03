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
    void canonicalAttoWinsCollidingLegacyImports() throws Exception {
        Path importer = temp.resolve("entry.atto");
        Files.writeString(importer, "pub routine main(): void { return; }");
        Path attoOnly = temp.resolve("cache.atto");
        Files.writeString(attoOnly, "pub fnc cache(): int { return 42; }");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(attoOnly.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache").orElseThrow());
        assertEquals(attoOnly.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache.atto").orElseThrow());

        Path legacy = temp.resolve("cache.ores");
        Files.writeString(legacy, "pub fnc cache(): int { return 17; }");
        assertEquals(attoOnly.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache").orElseThrow());
        assertEquals(attoOnly.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache.atto").orElseThrow());
        // Legacy sources must be named explicitly when a canonical twin exists.
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache.ores").orElseThrow());
        Path javaTwin = temp.resolve("cache.java");
        Files.writeString(javaTwin, "final class Cache {}");
        assertEquals(attoOnly.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache").orElseThrow());
        assertEquals(javaTwin.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./cache.java").orElseThrow());
        assertTrue(config.resolveImport(importer, "./missing.atto").isEmpty());
    }

    @Test
    void canonicalAttoManifestBeatsLegacyTwinInSameDirectory() throws Exception {
        Path project = temp.resolve("new-project");
        Path src = project.resolve("src");
        Files.createDirectories(src);
        Path atto = src.resolve("main.atto");
        Files.writeString(atto, "pub fnc main(): void { return; }");
        Files.writeString(project.resolve(OresProjectConfig.LEGACY_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "legacy"
                [entrypoints]
                main = "src/old.ores"
                """);
        Path canonical = project.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(canonical, """
                schema_version = "1"
                [project]
                name = "attolang"
                [entrypoints]
                main = "src/main.atto"
                """);

        OresProjectConfig cfg = OresProjectConfig.discover(atto, Map.of());
        assertEquals(canonical.toAbsolutePath().normalize(), cfg.manifestPath().orElseThrow());
        assertEquals("attolang", cfg.projectName().orElseThrow());
        assertEquals(atto.toAbsolutePath().normalize(), cfg.mainEntrypoint().orElseThrow());
    }

    @Test
    void oldManifestIsAnExplicitMigrationFallbackNotAReplacement() throws Exception {
        Path entry = temp.resolve("old.ores");
        Files.writeString(entry, "pub fnc main(): void { return; }");
        Path legacy = temp.resolve(OresProjectConfig.LEGACY_MANIFEST_NAME);
        Files.writeString(legacy, """
                schema_version = "1"
                [project]
                name = "legacy-only"
                """);
        OresProjectConfig cfg = OresProjectConfig.discover(entry, Map.of());
        assertEquals(legacy.toAbsolutePath().normalize(), cfg.manifestPath().orElseThrow());
        assertEquals("legacy-only", cfg.projectName().orElseThrow());
    }

    @Test
    void attolangPathOverridesLegacyEnvironmentWithoutCombiningRoots() throws Exception {
        Path canonical = temp.resolve("canonical");
        Path legacy = temp.resolve("legacy");
        Files.createDirectories(canonical);
        Files.createDirectories(legacy);
        Path expected = canonical.resolve("lib.atto");
        Files.writeString(expected, "pub fnc identity(): int { return 42; }");
        Files.writeString(legacy.resolve("lib.ores"), "pub fnc identity(): int { return 17; }");
        Path importer = temp.resolve("main.atto");
        Files.writeString(importer, "pub fnc main(): void { return; }");

        OresProjectConfig cfg = OresProjectConfig.discover(importer, Map.of(
                OresProjectConfig.ENV_ATTOLANG_PATH, canonical.toString(),
                OresProjectConfig.ENV_ORESLANG_PATH, legacy.toString()));
        assertEquals(java.util.List.of(canonical.toAbsolutePath().normalize()),
                cfg.environmentPaths());
        assertEquals(expected.toAbsolutePath().normalize(),
                cfg.resolveImport(importer, "lib").orElseThrow());

        // An explicitly empty canonical setting cannot silently activate a
        // potentially attacker-controlled legacy ambient import directory.
        OresProjectConfig empty = OresProjectConfig.discover(importer, Map.of(
                OresProjectConfig.ENV_ATTOLANG_PATH, "",
                OresProjectConfig.ENV_ORESLANG_PATH, legacy.toString()));
        assertTrue(empty.environmentPaths().isEmpty());
        assertTrue(empty.resolveImport(importer, "lib").isEmpty());
    }

    @Test
    void canonicalAttoUsesSearchRootPrecedenceWithoutEscapingRoot() throws Exception {
        Path source = temp.resolve("source");
        Path ambient = temp.resolve("ambient");
        Files.createDirectories(source.resolve("pkg"));
        Files.createDirectories(ambient.resolve("pkg"));
        Path importer = source.resolve("main.atto");
        Files.writeString(importer, "pub fnc main(): void { return; }");
        Path localLegacy = source.resolve("pkg/value.ores");
        Path ambientAtto = ambient.resolve("pkg/value.atto");
        Files.writeString(localLegacy, "pub fnc value(): int { return 17; }");
        Files.writeString(ambientAtto, "pub fnc value(): int { return 42; }");
        Path cfgPath = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(cfgPath, """
                schema_version = "1"
                [source]
                roots = ["source"]
                """);

        OresProjectConfig cfg = OresProjectConfig.discover(importer,
                Map.of(OresProjectConfig.ENV_ATTOLANG_PATH, ambient.toString()));
        // Root locality is stronger than extension priority across roots.
        assertEquals(localLegacy.toAbsolutePath().normalize(),
                cfg.resolveImport(importer, "pkg/value").orElseThrow());
        Path localAtto = source.resolve("pkg/value.atto");
        Files.writeString(localAtto, "pub fnc value(): int { return 1; }");
        assertEquals(localAtto.toAbsolutePath().normalize(),
                cfg.resolveImport(importer, "pkg/value").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> cfg.resolveImport(importer, "pkg/../../outside"));
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
}
