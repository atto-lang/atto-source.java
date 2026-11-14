package dev.oreslang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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
    void attoImportsArePreferredAndExplicitLegacyPathsDoNotAlias() throws Exception {
        Path importer = temp.resolve("main.atto");
        Files.writeString(importer, "pub fnc main(): void { return; }");
        Path atto = temp.resolve("replay_stream.atto");
        Path legacy = temp.resolve("replay_stream.ores");
        Files.writeString(atto, "pub fnc current(): int { return 2; }");
        Files.writeString(legacy, "pub fnc legacy(): int { return 1; }");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(atto.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./replay_stream").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./replay_stream.ores").orElseThrow());
        Files.delete(atto);
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./replay_stream").orElseThrow());
        assertTrue(config.resolveImport(importer, "./replay_stream.atto").isEmpty());
    }

    @Test
    void canonicalAttoImportsWinWithoutBreakingExplicitLegacyImports() throws Exception {
        Path src = temp.resolve("atto-src");
        Path pkg = src.resolve("pkg");
        Files.createDirectories(pkg);
        Path importer = src.resolve("main.atto");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Path canonical = pkg.resolve("value.atto");
        Path legacy = pkg.resolve("value.ores");
        Files.writeString(canonical, "pub fnc value() => int { return 1; }");
        Files.writeString(legacy, "pub fnc value() => int { return 2; }");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(canonical.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./pkg/value").orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./pkg/value.ores").orElseThrow());
        assertEquals(canonical.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./pkg/value.atto").orElseThrow());

        Path uppercase = pkg.resolve("upper.ATTO");
        Files.writeString(uppercase, "pub fnc value(): int { return 3; }");
        assertEquals(uppercase.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./pkg/upper.ATTO").orElseThrow());

        Files.delete(canonical);
        assertEquals(legacy.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./pkg/value").orElseThrow());
    }


    @Test
    void nonRegularCanonicalAttoEntryDoesNotFallBackToLegacySibling() throws Exception {
        Path src = temp.resolve("directory-atto-src");
        Files.createDirectories(src);
        Path importer = src.resolve("main.atto");
        Path canonicalDirectory = src.resolve("helper.atto");
        Path legacy = src.resolve("helper.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.createDirectory(canonicalDirectory);
        Files.writeString(legacy, "pub fnc helper() => int { return 41; }");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        Path selected = config.resolveImport(importer, "./helper").orElseThrow();
        assertEquals(canonicalDirectory.toAbsolutePath().normalize(), selected);
        assertTrue(Files.isDirectory(selected),
                "canonical .atto directory must be selected for later non-regular-file rejection");
    }

    @Test
    void danglingCanonicalAttoEntryDoesNotFallBackToLegacySibling() throws Exception {
        Path src = temp.resolve("dangling-atto-src");
        Files.createDirectories(src);
        Path importer = src.resolve("main.atto");
        Path dangling = src.resolve("helper.atto");
        Path legacy = src.resolve("helper.ores");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(legacy, "pub fnc helper() => int { return 41; }");
        try {
            Files.createSymbolicLink(dangling, Path.of("missing-atto-target"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "filesystem does not permit symlink fixtures: " + unsupported);
        }
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(dangling.toAbsolutePath().normalize(),
                config.resolveImport(importer, "./helper").orElseThrow());
    }

    @Test
    void foreignFilesAreNeverAcceptedAsLanguageImports() throws Exception {
        Path src = temp.resolve("foreign-src");
        Files.createDirectories(src);
        Path importer = src.resolve("main.atto");
        Files.writeString(importer, "pub fnc main() => void { return; }");
        Files.writeString(src.resolve("payload.py"), "print('unsafe')\n");
        Files.writeString(src.resolve("payload.txt"), "not source\n");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertTrue(config.resolveImport(importer, "./payload.py").isEmpty());
        assertTrue(config.resolveImport(importer, "./payload.txt").isEmpty());
        assertTrue(config.resolveImport(importer, "./payload").isEmpty());
        assertTrue(config.resolveImport(importer, "./missing.atto").isEmpty());
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
    void attoPackageManifestDefinesProjectRootImportsAndEntrypoint() throws Exception {
        Path project = temp.resolve("atto-package");
        Path src = project.resolve("src");
        Path tests = project.resolve("tests");
        Files.createDirectories(src.resolve("pkg"));
        Files.createDirectories(tests);
        Path entry = tests.resolve("smoke.atto");
        Path library = src.resolve("pkg/cache.atto");
        Files.writeString(entry, "pub routine main() -> int { return 0; }");
        Files.writeString(library, "pub fnc value(): int { return 42; }");
        Path manifest = project.resolve(OresProjectConfig.ATTO_MANIFEST_NAME);
        Files.writeString(manifest, """
                schema_version = "1"
                [project]
                name = "atto-cache"
                root = "."
                [source]
                roots = ["src"]
                [entrypoints]
                main = "tests/smoke.atto"
                [build]
                library_profile = "std"
                """);
        OresProjectConfig config = OresProjectConfig.discover(entry, Map.of());
        assertEquals(manifest.toAbsolutePath().normalize(), config.manifestPath().orElseThrow());
        assertEquals(project.toAbsolutePath().normalize(), config.projectRoot());
        assertEquals(src.toAbsolutePath().normalize(), config.sourceRoots().getFirst());
        assertEquals(entry.toAbsolutePath().normalize(), config.mainEntrypoint().orElseThrow());
        assertEquals(library.toAbsolutePath().normalize(),
                config.resolveImport(entry, "pkg/cache").orElseThrow());
        assertEquals(OresProjectConfig.LibraryProfile.STD, config.libraryProfile());
    }

    @Test
    void ambiguousAttoAndLegacyManifestsAtSameDepthFailClosed() throws Exception {
        Path atto = temp.resolve(OresProjectConfig.ATTO_MANIFEST_NAME);
        Path legacy = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(atto, "schema_version = \"1\"\n");
        Files.writeString(legacy, "schema_version = \"1\"\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(temp.resolve("entry.atto"), Map.of()));
        assertTrue(failure.getMessage().contains("ambiguous"));
        assertTrue(failure.getMessage().contains(OresProjectConfig.ATTO_MANIFEST_NAME));
        assertTrue(failure.getMessage().contains(OresProjectConfig.MANIFEST_NAME));
    }

    @Test
    void nearestManifestWinsRegardlessOfLegacySpelling() throws Exception {
        Path parent = temp.resolve("parent");
        Path nested = parent.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(parent.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "outer-legacy"
                """);
        Files.writeString(nested.resolve(OresProjectConfig.ATTO_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "inner-atto"
                """);
        OresProjectConfig config = OresProjectConfig.discover(nested.resolve("main.atto"), Map.of());
        assertEquals("inner-atto", config.projectName().orElseThrow());
        assertEquals(nested.resolve(OresProjectConfig.ATTO_MANIFEST_NAME).toAbsolutePath().normalize(),
                config.manifestPath().orElseThrow());
    }

    @Test
    void malformedOrSymlinkedAttoManifestCannotFallBackToAmbientSourceRoot() throws Exception {
        Path manifest = temp.resolve(OresProjectConfig.ATTO_MANIFEST_NAME);
        Files.writeString(manifest, "schema_version = \"999\"\n");
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(temp.resolve("main.atto"), Map.of()));
        assertTrue(malformed.getMessage().contains("schema_version"));
        Files.delete(manifest);
        Path actual = temp.resolve("manifest-real.toml");
        Files.writeString(actual, "schema_version = \"1\"\n");
        try {
            Files.createSymbolicLink(manifest, actual);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not allow symlink fixture: " + unavailable.getClass().getSimpleName());
        }
        IllegalArgumentException symlink = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(temp.resolve("main.atto"), Map.of()));
        assertTrue(symlink.getMessage().contains("symlinked"));
    }

}
