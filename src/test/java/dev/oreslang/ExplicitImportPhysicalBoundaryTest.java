package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ExplicitImportPhysicalBoundaryTest {
    @TempDir Path temp;

    @Test
    void manifestRelativeImportCannotEscapePhysicalProjectRoot() throws Exception {
        Path project = manifestProject("relative-parent-escape");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path outside = temp.resolve("outside.ores");
        Files.writeString(outside, "pub fnc outside(): int { return 42; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "../../outside"));
        assertTrue(failure.getMessage().contains("escapes physical project root"),
                failure.getMessage());
    }

    @Test
    void manifestRelativeFileSymlinkCannotEscapeProjectRoot() throws Exception {
        Path project = manifestProject("relative-file-symlink");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path outside = temp.resolve("file-secret.ores");
        Files.writeString(outside, "pub fnc secret(): int { return 7; }\n");
        requireSymlink(src.resolve("secret.ores"), outside);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./secret"));
        assertTrue(failure.getMessage().contains("escapes physical project root"),
                failure.getMessage());
    }

    @Test
    void manifestRelativeDirectorySymlinkCannotEscapeProjectRoot() throws Exception {
        Path project = manifestProject("relative-dir-symlink");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path outside = Files.createDirectories(temp.resolve("external-dir"));
        Files.writeString(outside.resolve("secret.ores"),
                "pub fnc secret(): int { return 7; }\n");
        requireSymlink(src.resolve("external"), outside);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./external/secret"));
        assertTrue(failure.getMessage().contains("escapes physical project root"),
                failure.getMessage());
    }

    @Test
    void manifestRelativeImportCannotCrossNestedPackageMarker() throws Exception {
        Path project = manifestProject("relative-nested-package");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path child = Files.createDirectories(src.resolve("child"));
        Files.writeString(child.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(child.resolve("secret.ores"),
                "pub fnc secret(): int { return 7; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./child/secret"));
        assertTrue(failure.getMessage().contains("nested package boundary"),
                failure.getMessage());
    }


    @Test
    void manifestRelativeImportUsesPhysicalImporterAndRejectsOutsideSibling() throws Exception {
        Path project = manifestProject("physical-importer");
        Path src = Files.createDirectories(project.resolve("src"));
        Path outside = Files.createDirectories(temp.resolve("physical-importer-outside"));
        Path physicalImporter = outside.resolve("main.ores");
        Files.writeString(physicalImporter, "pub fnc main(): void { return; }\n");
        Files.writeString(outside.resolve("helper.ores"),
                "pub fnc helper(): int { return 42; }\n");

        Path importer = src.resolve("main.ores");
        requireSymlink(importer, physicalImporter);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./helper"));
        assertTrue(failure.getMessage().contains("escapes physical project root"),
                failure.getMessage());
    }

    @Test
    void manifestRelativeInRootAliasUsesCanonicalPhysicalIdentity() throws Exception {
        Path project = manifestProject("relative-canonical-alias");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path target = src.resolve("real.ores");
        Files.writeString(target, "pub fnc real(): int { return 42; }\n");
        Path alias = src.resolve("alias.ores");
        requireSymlink(alias, target);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(target.toRealPath(),
                config.resolveImport(importer, "./alias").orElseThrow());
    }

    @Test
    void absoluteImportRemainsExplicitButCanonicalizesPhysicalIdentity() throws Exception {
        Path project = manifestProject("absolute-explicit");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path outside = temp.resolve("absolute-outside.ores");
        Files.writeString(outside, "pub fnc outside(): int { return 42; }\n");
        Path alias = temp.resolve("absolute-alias.ores");
        requireSymlink(alias, outside);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(outside.toRealPath(),
                config.resolveImport(importer, alias.toString()).orElseThrow());
    }

    @Test
    void noManifestParentRelativeImportRemainsSupportedAndCanonical() throws Exception {
        Path app = Files.createDirectories(temp.resolve("no-manifest/app"));
        Path shared = Files.createDirectories(temp.resolve("no-manifest/shared"));
        Path importer = app.resolve("main.ores");
        Path target = shared.resolve("value.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");
        Files.writeString(target, "pub fnc value(): int { return 42; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertTrue(config.manifestPath().isEmpty());
        assertEquals(target.toRealPath(),
                config.resolveImport(importer, "../shared/value").orElseThrow());
    }

    @Test
    void danglingExplicitSourceAliasFailsClosed() throws Exception {
        Path project = manifestProject("dangling-explicit");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");
        Path alias = src.resolve("dangling.ores");
        requireSymlink(alias, src.resolve("missing.ores"));

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./dangling"));
        assertTrue(failure.getMessage().contains("cannot verify physical source path"),
                failure.getMessage());
    }


    @Test
    void dotHiddenBareImportUsesConfiguredSearchRoot() throws Exception {
        Path project = manifestProject("dot-hidden-bare");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path ambient = Files.createDirectories(temp.resolve("dot-hidden-ambient/.hidden"));
        Path target = ambient.resolve("util.ores");
        Files.writeString(target, "pub fnc util(): int { return 42; }\n");

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH,
                        temp.resolve("dot-hidden-ambient").toString()));
        assertEquals(target.toRealPath(),
                config.resolveImport(importer, ".hidden/util").orElseThrow());
    }

    @Test
    void dotDotPrefixedBareImportUsesConfiguredSearchRoot() throws Exception {
        Path project = manifestProject("dotdot-prefixed-bare");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path ambientRoot = temp.resolve("dotdot-prefixed-ambient");
        Path ambient = Files.createDirectories(ambientRoot.resolve("..backup"));
        Path target = ambient.resolve("util.ores");
        Files.writeString(target, "pub fnc util(): int { return 42; }\n");

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, ambientRoot.toString()));
        assertEquals(target.toRealPath(),
                config.resolveImport(importer, "..backup/util").orElseThrow());
    }


    @Test
    void linkedRunnerPreservesLexicalProjectAuthorityForSymlinkedEntry() throws Exception {
        Path project = manifestProject("linked-symlink-entry");
        Path src = Files.createDirectories(project.resolve("src"));
        Path outside = Files.createDirectories(temp.resolve("linked-symlink-entry-outside"));
        Path physicalEntry = outside.resolve("main.ores");
        Files.writeString(physicalEntry, """
                import * as helper from "./helper.ores";
                pub routine main(): void { return; }
                """);
        Files.writeString(outside.resolve("helper.ores"),
                "pub fnc helper(): int { return 42; }\n");

        Path lexicalEntry = src.resolve("main.ores");
        requireSymlink(lexicalEntry, physicalEntry);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(lexicalEntry, Map.of()));
        assertTrue(failure.getMessage().contains("escapes physical project root"),
                failure.getMessage());
    }

    @Test
    void relativeImportCannotReachNestedPackageThroughDirectoryAlias() throws Exception {
        Path project = manifestProject("nested-package-alias");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path child = Files.createDirectories(src.resolve("child"));
        Files.writeString(child.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(child.resolve("secret.ores"),
                "pub fnc secret(): int { return 7; }\n");
        requireSymlink(src.resolve("alias"), child);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./alias/secret"));
        assertTrue(failure.getMessage().contains("nested package boundary"),
                failure.getMessage());
    }

    @Test
    void malformedNestedPackageMarkerStillClaimsBoundary() throws Exception {
        Path project = manifestProject("malformed-nested-marker");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path child = Files.createDirectories(src.resolve("child"));
        Files.writeString(child.resolve(OresProjectConfig.MANIFEST_NAME),
                "this is not valid toml = [\n");
        Files.writeString(child.resolve("secret.ores"),
                "pub fnc secret(): int { return 7; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./child/secret"));
        assertTrue(failure.getMessage().contains("nested package boundary"),
                failure.getMessage());
    }

    @Test
    void danglingNestedPackageMarkerStillClaimsBoundary() throws Exception {
        Path project = manifestProject("dangling-nested-marker");
        Path src = Files.createDirectories(project.resolve("src"));
        Path importer = src.resolve("main.ores");
        Files.writeString(importer, "pub fnc main(): void { return; }\n");

        Path child = Files.createDirectories(src.resolve("child"));
        requireSymlink(
                child.resolve(OresProjectConfig.MANIFEST_NAME),
                child.resolve("missing-manifest.toml"));
        Files.writeString(child.resolve("secret.ores"),
                "pub fnc secret(): int { return 7; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "./child/secret"));
        assertTrue(failure.getMessage().contains("nested package boundary"),
                failure.getMessage());
    }

    private Path manifestProject(String name) throws IOException {
        Path project = Files.createDirectories(temp.resolve(name));
        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        return project;
    }

    private static void requireSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "symlinks unavailable: " + unsupported);
        } catch (IOException failure) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                assumeTrue(false, "symlinks unavailable: " + failure);
            }
            throw failure;
        }
    }
}
