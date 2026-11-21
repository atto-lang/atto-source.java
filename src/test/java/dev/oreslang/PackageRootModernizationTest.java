package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
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

final class PackageRootModernizationTest {
    @TempDir Path temp;

    @Test
    void modernManifestWinsOverLegacyMarkerInSameDirectory() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-wins"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "legacy-root"
                """);
        Files.createDirectories(root.resolve("legacy-root"));
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "modern"
                version = "1.0.0"
                """);

        OresProjectConfig config = OresProjectConfig.discover(source, Map.of());
        assertEquals(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                config.manifestPath().orElseThrow());
        assertEquals(root.toRealPath(), config.projectRoot());
        assertEquals("modern", config.projectName().orElseThrow());
    }

    @Test
    void malformedModernMarkerBlocksLegacyFallback() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-malformed"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.createDirectory(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("regular non-symlink"),
                failure.getMessage());
    }

    @Test
    void danglingModernMarkerBlocksLegacyFallback() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-dangling"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        requireSymlink(
                root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                root.resolve("missing.toml"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("regular non-symlink"),
                failure.getMessage());
    }


    @Test
    void legacyManifestSymlinkRemainsSupportedDuringMigration() throws Exception {
        Path root = Files.createDirectories(temp.resolve("legacy-symlink"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");

        Path actual = temp.resolve("legacy-config.toml");
        Files.writeString(actual, "schema_version = \"1\"\n");
        requireSymlink(root.resolve(OresProjectConfig.MANIFEST_NAME), actual);

        OresProjectConfig config = OresProjectConfig.discover(source, Map.of());
        assertEquals(root.resolve(OresProjectConfig.MANIFEST_NAME),
                config.manifestPath().orElseThrow());
    }


    @Test
    void modernManifestSnapshotRejectsOversizeAndInvalidUtf8() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-manifest-bytes"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Path manifest = root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME);

        Files.writeString(manifest, "x".repeat(65_537));
        IllegalArgumentException oversized = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(oversized.getMessage().contains("64 KiB"),
                oversized.getMessage());

        Files.write(manifest, new byte[] {(byte) 0xc3, (byte) 0x28});
        IllegalArgumentException invalidUtf8 = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(invalidUtf8.getMessage().contains("valid UTF-8"),
                invalidUtf8.getMessage());
    }

    @Test
    void modernPackageRootIsManifestDirectoryNotProjectRootRedirect() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-root-fixed"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "elsewhere"
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("anchors the package root"),
                failure.getMessage());
    }


    @Test
    void modernManifestConfiguredPathsCannotEscapePhysicalPackageRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-configured-paths"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");

        for (String body : new String[] {
                "[source]\nroots = [\"../outside\"]\n",
                "[source]\nimport_paths = [\"../outside\"]\n",
                "[entrypoints]\nmain = \"../outside/main.ores\"\n",
                "[source]\nroots = [\"C:/outside\"]\n",
                "[source]\nimport_paths = [\"lib\\\\escape\"]\n"
        }) {
            Files.writeString(
                    root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                    "schema_version = \"1\"\n" + body);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OresProjectConfig.discover(source, Map.of()),
                    body);
        }
    }

    @Test
    void modernManifestConfiguredPathCannotEscapeThroughSymlinkedAncestor() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-configured-symlink"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");

        Path outside = Files.createDirectories(temp.resolve("modern-configured-symlink-outside"));
        requireSymlink(root.resolve("alias"), outside);
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["alias/generated"]
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("escapes the physical package root"),
                failure.getMessage());
    }

    @Test
    void modernManifestConfiguredPathCannotCrossNestedPackage() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-configured-nested"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(
                child.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["child"]
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("nested package boundary"),
                failure.getMessage());
    }

    @Test
    void canonicalPlusPathResolvesFromPhysicalPackageRoot() throws Exception {
        Path root = modernPackage("plus-canonical");
        Path importer = root.resolve("src/main.ores");
        Path target = root.resolve("lib/telemetry.ores");
        Files.createDirectories(importer.getParent());
        Files.createDirectories(target.getParent());
        Files.writeString(importer, "pub routine main(): void { return; }\n");
        Files.writeString(target, "pub fnc telemetry(): int { return 42; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(target.toRealPath(),
                config.resolveImport(importer, "+lib/telemetry").orElseThrow());
    }

    @Test
    void retiredPlusSlashAndMalformedRootSpellingsReject() throws Exception {
        Path root = modernPackage("plus-spelling");
        Path importer = root.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());

        for (String specifier : new String[] {
                "+", "+/lib/value", "+../value", "+lib/../value",
                "+lib//value", "+lib/./value", "+lib\\value"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> config.resolveImport(importer, specifier),
                    specifier);
        }
    }

    @Test
    void legacyManifestCannotAuthorizeProjectRootImport() throws Exception {
        Path root = Files.createDirectories(temp.resolve("legacy-no-plus"));
        Path importer = root.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+lib/value"));
        assertTrue(failure.getMessage().contains(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                failure.getMessage());
    }

    @Test
    void projectRootImportRejectsFileAndDirectorySymlinkEscape() throws Exception {
        Path root = modernPackage("plus-symlink-escape");
        Path importer = root.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");
        Path outside = Files.createDirectories(temp.resolve("plus-outside"));
        Path secret = outside.resolve("secret.ores");
        Files.writeString(secret, "pub fnc secret(): int { return 7; }\n");

        Files.createDirectories(root.resolve("lib"));
        requireSymlink(root.resolve("lib/file.ores"), secret);
        requireSymlink(root.resolve("lib/dir"), outside);

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        for (String specifier : new String[] {"+lib/file", "+lib/dir/secret"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> config.resolveImport(importer, specifier),
                    specifier);
            assertTrue(failure.getMessage().contains("escapes physical configured search root"),
                    failure.getMessage());
        }
    }

    @Test
    void projectRootImportCannotCrossNestedModernOrLegacyPackage() throws Exception {
        Path root = modernPackage("plus-nested");
        Path importer = root.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");

        Path modernChild = Files.createDirectories(root.resolve("modern-child"));
        Files.writeString(modernChild.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(modernChild.resolve("secret.ores"),
                "pub fnc secret(): int { return 1; }\n");

        Path legacyChild = Files.createDirectories(root.resolve("legacy-child"));
        Files.writeString(legacyChild.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(legacyChild.resolve("secret.ores"),
                "pub fnc secret(): int { return 2; }\n");

        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        for (String specifier : new String[] {
                "+modern-child/secret", "+legacy-child/secret"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> config.resolveImport(importer, specifier),
                    specifier);
            assertTrue(failure.getMessage().contains("nested package boundary"),
                    failure.getMessage());
        }
    }

    @Test
    void oresPathsPrecedesLegacyOreslangPathAndEmptyEntriesGrantNothing() throws Exception {
        Path app = Files.createDirectories(temp.resolve("ambient/app"));
        Path importer = app.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");

        Path modern = Files.createDirectories(temp.resolve("ambient/modern"));
        Path legacy = Files.createDirectories(temp.resolve("ambient/legacy"));
        Path modernTarget = modern.resolve("shared.ores");
        Path legacyTarget = legacy.resolve("shared.ores");
        Files.writeString(modernTarget, "pub fnc shared(): int { return 1; }\n");
        Files.writeString(legacyTarget, "pub fnc shared(): int { return 2; }\n");

        OresProjectConfig config = OresProjectConfig.discover(
                importer,
                Map.of(
                        OresProjectConfig.ENV_ORES_PATHS,
                        ":" + modern + "::",
                        OresProjectConfig.ENV_ORESLANG_PATH,
                        legacy.toString()));

        assertEquals(modernTarget.toRealPath(),
                config.resolveImport(importer, "shared").orElseThrow());
    }


    @Test
    void projectRootAuthorityFollowsTheImportersNearestModernPackage() throws Exception {
        Path entryRoot = modernPackage("entry-package");
        Path entry = entryRoot.resolve("main.ores");
        Files.writeString(entry, "pub routine main(): void { return; }\n");

        Path dependency = modernPackage("dependency-package");
        Path dependencyImporter = dependency.resolve("src/worker.ores");
        Path dependencyTarget = dependency.resolve("lib/helper.ores");
        Files.createDirectories(dependencyImporter.getParent());
        Files.createDirectories(dependencyTarget.getParent());
        Files.writeString(dependencyImporter, "pub fnc worker(): void { return; }\n");
        Files.writeString(dependencyTarget, "pub fnc helper(): int { return 42; }\n");

        OresProjectConfig entryConfig = OresProjectConfig.discover(entry, Map.of());
        assertEquals(
                dependencyTarget.toRealPath(),
                entryConfig.resolveImport(dependencyImporter, "+lib/helper").orElseThrow());
    }

    @Test
    void nestedLegacyPackageBlocksParentProjectRootAuthority() throws Exception {
        Path root = modernPackage("parent-modern-nested-legacy");
        Path nested = Files.createDirectories(root.resolve("nested"));
        Files.writeString(
                nested.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Path importer = nested.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");

        Path parentTarget = root.resolve("lib/helper.ores");
        Files.createDirectories(parentTarget.getParent());
        Files.writeString(parentTarget, "pub fnc helper(): int { return 42; }\n");

        OresProjectConfig parentConfig = OresProjectConfig.discover(root.resolve("entry.ores"), Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> parentConfig.resolveImport(importer, "+lib/helper"));
        assertTrue(failure.getMessage().contains("nearest package authority"),
                failure.getMessage());
    }

    @Test
    void malformedNearestModernMarkerBlocksParentProjectRootAuthority() throws Exception {
        Path root = modernPackage("parent-modern-malformed-child");
        Path nested = Files.createDirectories(root.resolve("nested"));
        Files.createDirectory(nested.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME));
        Path importer = nested.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");

        OresProjectConfig parentConfig = OresProjectConfig.discover(root.resolve("entry.ores"), Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> parentConfig.resolveImport(importer, "+lib/helper"));
        assertTrue(failure.getMessage().contains("regular non-symlink"),
                failure.getMessage());
    }


    @Test
    void danglingNestedLegacyMarkerBlocksParentProjectRootAuthority() throws Exception {
        Path root = modernPackage("parent-modern-dangling-legacy");
        Path nested = Files.createDirectories(root.resolve("nested"));
        requireSymlink(
                nested.resolve(OresProjectConfig.MANIFEST_NAME),
                nested.resolve("missing-legacy.toml"));
        Path importer = nested.resolve("main.ores");
        Files.writeString(importer, "pub routine main(): void { return; }\n");

        OresProjectConfig parentConfig =
                OresProjectConfig.discover(root.resolve("entry.ores"), Map.of());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> parentConfig.resolveImport(importer, "+lib/helper"));
        assertTrue(failure.getMessage().contains("blocked by nearer legacy package authority"),
                failure.getMessage());
    }

    @Test
    void modernConfiguredMissingPathRejectsNonDirectoryAncestor() throws Exception {
        Path root = Files.createDirectories(temp.resolve("modern-file-ancestor"));
        Path source = root.resolve("main.ores");
        Files.writeString(source, "pub routine main(): void { return; }\n");
        Files.writeString(root.resolve("blocked"), "not a directory\n");
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["blocked/generated"]
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.discover(source, Map.of()));
        assertTrue(failure.getMessage().contains("non-directory ancestor"),
                failure.getMessage());
    }

    @Test
    void linkedRunnerResolvesCanonicalProjectRootImport() throws Exception {
        Path root = modernPackage("linked-plus");
        Path importer = root.resolve("src/main.ores");
        Path target = root.resolve("lib/helper.ores");
        Files.createDirectories(importer.getParent());
        Files.createDirectories(target.getParent());
        Files.writeString(importer, """
                import fnc helper from "+lib/helper";
                pub routine main(): void { return; }
                """);
        Files.writeString(target, """
                pub fnc helper(): int { return 42; }
                """);

        IncrementalCompiler.BuildResult build =
                LinkedProgramRunner.validate(importer, Map.of());
        assertEquals(2, build.units().size());
    }

    @Test
    void missingProjectRootImportFailsClosedInLinkedRunner() throws Exception {
        Path root = modernPackage("linked-plus-missing");
        Path importer = root.resolve("main.ores");
        Files.writeString(importer, """
                import fnc missing from "+lib/missing";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(importer, Map.of()));
        assertTrue(failure.getMessage().contains("filesystem import"),
                failure.getMessage());
    }

    @Test
    void sourceMapCompilerRequiresExplicitResolverMappingForProjectRootImport() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = Map.of(
                "main.ores",
                """
                import fnc helper from "+lib/helper";
                pub routine main(): void { return; }
                """,
                "lib/helper.ores",
                "pub fnc helper(): int { return 42; }\n");

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(sources));
        assertTrue(missing.getMessage().contains("requires a package-root resolver mapping"),
                missing.getMessage());

        IncrementalCompiler.BuildResult linked = compiler.compile(
                sources,
                Map.of("main.ores", Map.of("+lib/helper", "lib/helper.ores")));
        assertEquals(2, linked.units().size());
    }

    private Path modernPackage(String name) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        return root;
    }

    private static void requireSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "symlinks unavailable: " + unavailable);
        } catch (IOException failure) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                assumeTrue(false, "symlinks unavailable: " + failure);
            }
            throw failure;
        }
    }
}
