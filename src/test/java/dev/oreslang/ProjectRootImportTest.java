package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectRootImportTest {
    @TempDir Path temp;

    private Path makeProject(String name) throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("src/routes/rest/foo/bar/baz"));
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "project-root-test"
                [source]
                roots = ["src"]
                """);
        Files.writeString(root.resolve("lib/message.ores"), """
                pub fnc message(): string { return "root-resolved"; }
                """);
        return root;
    }

    @Test
    void deeplyNestedImportResolvesAgainstManifestNotImporterOrCwd() throws Exception {
        Path root = makeProject("nested");
        Path entry = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        Files.writeString(entry, """
                import fnc message from "+/lib/message";
                pub fnc main(): void {
                  stdio.println(message());
                  return;
                }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), stdout, new ByteArrayOutputStream());
        assertEquals(2, result.units().size());
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("root-resolved"));
    }

    @Test
    void explicitRootOverrideChoosesOnlyTheOverriddenProject() throws Exception {
        Path first = makeProject("first");
        Path second = makeProject("second");
        Path importer = first.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        Path secondTarget = second.resolve("lib/message.ores");

        OresProjectConfig local = OresProjectConfig.discover(importer, Map.of());
        assertEquals(first.resolve("lib/message.ores"), local.resolveImport(importer, "+/lib/message").orElseThrow());
        OresProjectConfig overridden = OresProjectConfig.discover(
                importer, Map.of(OresProjectConfig.ENV_PROJECT_ROOT, second.toString()));
        assertEquals(secondTarget, overridden.resolveImport(importer, "+/lib/message").orElseThrow());
    }

    @Test
    void missingExplicitImportFailsInsteadOfFallingBackToVendorPaths() throws Exception {
        Path root = makeProject("missing");
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        Files.writeString(importer, """
                import fnc missing from "+/lib/missing";
                pub fnc main(): void { return; }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(importer));
        assertTrue(failure.getMessage().contains("does not resolve to a file"), failure.getMessage());
    }

    @Test
    void invalidRootPathsCannotTraverseOrUseAmbiguousPrefix() throws Exception {
        Path root = makeProject("safety");
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        for (String name : new String[] {"+/../outside", "+/lib/../message", "+/", "+lib/message", "+/lib//message", "+\\lib\\message"}) {
            assertThrows(IllegalArgumentException.class, () -> config.resolveImport(importer, name), name);
        }
    }

    @Test
    void rootImportRequiresManifestAndRootOverrideMustContainManifest() throws Exception {
        Path importer = temp.resolve("standalone/main.ores");
        Files.createDirectories(importer.getParent());
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+/lib/message"));
        assertTrue(missing.getMessage().contains(OresProjectConfig.MANIFEST_NAME));
        assertThrows(IllegalArgumentException.class, () -> OresProjectConfig.discover(
                importer, Map.of(OresProjectConfig.ENV_PROJECT_ROOT, importer.getParent().toString())));
    }

    @Test
    void modernManifestWinsAtSameDirectoryAndLegacyRemainsSupported() throws Exception {
        Path root = makeProject("dual");
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        Files.writeString(root.resolve(OresProjectConfig.LEGACY_MANIFEST_NAME), """
                schema_version = "1"
                [project]
                name = "legacy"
                """);
        OresProjectConfig modern = OresProjectConfig.discover(importer, Map.of());
        assertEquals("project-root-test", modern.projectName().orElseThrow());
        Files.delete(root.resolve(OresProjectConfig.MANIFEST_NAME));
        OresProjectConfig legacy = OresProjectConfig.discover(importer, Map.of());
        assertEquals("legacy", legacy.projectName().orElseThrow());
        assertEquals(root.resolve("lib/message.ores"),
                legacy.resolveImport(importer, "+/lib/message").orElseThrow());
    }

    @Test
    void runtimeUsesExplicitRootOverrideNotImplicitImporterLocation() throws Exception {
        Path first = makeProject("runtime-first");
        Path second = makeProject("runtime-second");
        Path importer = first.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        Files.writeString(second.resolve("lib/message.ores"), """
                pub fnc message(): string { return "overridden-root"; }
                """);
        Files.writeString(importer, """
                import fnc message from "+/lib/message";
                pub fnc main(): void {
                  stdio.println(message());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(
                importer, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(OresProjectConfig.ENV_PROJECT_ROOT, second.toString()),
                out, new ByteArrayOutputStream());
        assertEquals(2, result.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("overridden-root"));
    }

    @Test
    void projectRootFieldControlsPlusImportAnchor() throws Exception {
        Path packageDirectory = temp.resolve("manifest-with-explicit-root");
        Path logicalRoot = packageDirectory.resolve("app");
        Files.createDirectories(logicalRoot.resolve("lib"));
        Files.createDirectories(packageDirectory.resolve("src"));
        Files.writeString(packageDirectory.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "app"
                """);
        Files.writeString(logicalRoot.resolve("lib/message.ores"), """
                pub fnc message(): string { return "configured-project-root"; }
                """);
        Path importer = packageDirectory.resolve("src/main.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(logicalRoot, config.projectRoot());
        assertEquals(logicalRoot.resolve("lib/message.ores"),
                config.resolveImport(importer, "+/lib/message").orElseThrow());
    }

    @Test
    void nestedPackageCannotInheritParentRootAuthority() throws Exception {
        Path root = makeProject("nested-boundary");
        Path child = root.resolve("lib/child");
        Files.createDirectories(child);
        Files.writeString(child.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "."
                """);
        Files.writeString(child.resolve("secret.ores"), """
                pub fnc secret(): int { return 42; }
                """);
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException boundary = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+/lib/child/secret"));
        assertTrue(boundary.getMessage().contains("nested package"), boundary.getMessage());

        // Even a symlink alias into an otherwise in-root nested package must
        // not bypass the package identity boundary.
        Path alias = root.resolve("lib/alias.ores");
        try {
            Files.createSymbolicLink(alias, child.resolve("secret.ores"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: " + unsupported);
        }
        IllegalArgumentException aliased = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+/lib/alias"));
        assertTrue(aliased.getMessage().contains("nested package"), aliased.getMessage());
    }

    @Test
    void projectManifestSymlinkCannotMoveRootAuthority() throws Exception {
        Path root = makeProject("symlink-manifest");
        Path outside = temp.resolve("outside-package.toml");
        Files.writeString(outside, """
                schema_version = "1"
                [project]
                root = "."
                """);
        Path manifest = root.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.delete(manifest);
        try {
            Files.createSymbolicLink(manifest, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: " + unsupported);
        }
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(importer, Map.of()));
        assertTrue(failure.getMessage().contains("symlink"), failure.getMessage());
    }

    @Test
    void inRootSymlinkSharesTheCanonicalSourceIdentity() throws Exception {
        Path root = makeProject("same-identity");
        Path original = root.resolve("lib/message.ores");
        Path alias = root.resolve("lib/alias.ores");
        try {
            Files.createSymbolicLink(alias, original);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: " + unsupported);
        }
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        assertEquals(original.toRealPath(), config.resolveImport(importer, "+/lib/alias").orElseThrow());
        assertEquals(config.resolveImport(importer, "+/lib/message"),
                config.resolveImport(importer, "+/lib/alias"));
    }

    @Test
    void directorySymlinkCannotEscapeProjectRoot() throws Exception {
        Path root = makeProject("directory-escape");
        Path external = temp.resolve("external");
        Files.createDirectories(external);
        Files.writeString(external.resolve("message.ores"), """
                pub fnc message(): string { return "bad"; }
                """);
        try {
            Files.createSymbolicLink(root.resolve("lib/external"), external);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: " + unsupported);
        }
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+/lib/external/message"));
        assertTrue(error.getMessage().contains("symlink"), error.getMessage());
    }

    @Test
    void symlinkCannotEscapeProjectRoot() throws Exception {
        Path root = makeProject("symlink");
        Path outside = temp.resolve("outside.ores");
        Files.writeString(outside, "pub fnc message(): string { return \"outside\"; }");
        Path link = root.resolve("lib/escape.ores");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink creation unavailable: " + unsupported);
        }
        Path importer = root.resolve("src/routes/rest/foo/bar/baz/delete.ores");
        OresProjectConfig config = OresProjectConfig.discover(importer, Map.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> config.resolveImport(importer, "+/lib/escape"));
        assertTrue(error.getMessage().contains("symlink"), error.getMessage());
    }
}
