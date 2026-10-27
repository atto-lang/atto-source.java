package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
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
