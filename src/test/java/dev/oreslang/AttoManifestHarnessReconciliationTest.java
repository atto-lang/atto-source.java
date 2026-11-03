package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.BoundedSourceReader;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonical package manifests and native AttoLang import resolution.
 * Host admission tests are separate from the final compiler-backed guest test.
 */
final class AttoManifestHarnessReconciliationTest {
    @TempDir Path temp;

    private static String manifest(String name) {
        return "schema_version = \"1\"\n[project]\nname = \"" + name
                + "\"\nroot = \".\"\n[source]\nroots = [\".\"]\n";
    }

    @Test
    void canonicalManifestOverridesLegacyInSameDirectory() throws Exception {
        Files.writeString(temp.resolve(".atto.pkg.toml"), manifest("canonical"));
        Files.writeString(temp.resolve(".oreslangc.cfg.toml"), manifest("legacy"));
        var project = OresProjectConfig.discover(temp.resolve("main.atto"), Map.of());
        assertEquals("canonical", project.projectName().orElseThrow());
        assertEquals(temp.resolve(".atto.pkg.toml").toAbsolutePath().normalize(),
                project.manifestPath().orElseThrow());
    }

    @Test
    void malformedCanonicalManifestCannotDowngrade() throws Exception {
        Files.writeString(temp.resolve(".atto.pkg.toml"), "schema_version = [\n");
        Files.writeString(temp.resolve(".oreslangc.cfg.toml"), manifest("legacy"));
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.discover(temp.resolve("main.atto"), Map.of()));
    }

    @Test
    void legacyManifestWorksOnlyWhenCanonicalMissing() throws Exception {
        Path legacy = temp.resolve(".oreslangc.cfg.toml");
        Files.writeString(legacy, manifest("legacy-only"));
        var found = OresProjectConfig.discover(temp.resolve("main.ores"), Map.of());
        assertEquals("legacy-only", found.projectName().orElseThrow());
        assertEquals(legacy.toAbsolutePath().normalize(), found.manifestPath().orElseThrow());
    }

    @Test
    void canonicalEnvironmentPathOverridesLegacyIncludingEmpty() throws Exception {
        Path canonical = temp.resolve("new");
        Path legacy = temp.resolve("old");
        var selected = OresProjectConfig.discover(temp,
                Map.of(OresProjectConfig.ENV_ATTOLANG_PATH, canonical.toString(),
                        OresProjectConfig.ENV_ORESLANG_PATH, legacy.toString()));
        assertEquals(java.util.List.of(canonical.toAbsolutePath().normalize()),
                selected.environmentPaths());
        var empty = OresProjectConfig.discover(temp,
                Map.of(OresProjectConfig.ENV_ATTOLANG_PATH, "",
                        OresProjectConfig.ENV_ORESLANG_PATH, legacy.toString()));
        assertTrue(empty.environmentPaths().isEmpty());
    }

    @Test
    void canonicalManifestSymlinkCannotDowngrade() throws Exception {
        Files.writeString(temp.resolve(".oreslangc.cfg.toml"), manifest("legacy"));
        Files.createSymbolicLink(temp.resolve(".atto.pkg.toml"),
                Path.of("nonexistent-canonical-manifest.toml"));
        assertThrows(SecurityException.class,
                () -> OresProjectConfig.discover(temp.resolve("main.atto"), Map.of()));
    }

    @Test
    void canonicalManifestDirectoryBlocksLegacyFallback() throws Exception {
        Files.createDirectory(temp.resolve(".atto.pkg.toml"));
        Files.writeString(temp.resolve(".oreslangc.cfg.toml"), manifest("legacy"));
        assertThrows(SecurityException.class,
                () -> OresProjectConfig.discover(temp.resolve("main.atto"), Map.of()));
    }

    @Test
    void canonicalAttoImporterRequiresExplicitLegacySuffix() throws Exception {
        Path entry = temp.resolve("main.atto");
        Path legacy = temp.resolve("helper.ores");
        Path canonical = temp.resolve("helper.atto");
        Files.writeString(entry, "pub routine main(): void { return; }");
        Files.writeString(legacy, "pub fnc helper(): int { return 1; }");
        var project = OresProjectConfig.discover(entry, Map.of());
        assertTrue(project.resolveImport(entry, "./helper").isEmpty());
        assertEquals(legacy.toAbsolutePath().normalize(),
                project.resolveImport(entry, "./helper.ores").orElseThrow());
        Files.createSymbolicLink(canonical, Path.of("missing-target.atto"));
        assertEquals(canonical.toAbsolutePath().normalize(),
                project.resolveImport(entry, "./helper").orElseThrow(),
                "dangling canonical entry must block .ores fallback");
        assertThrows(SecurityException.class, () -> BoundedSourceReader.readUtf8(canonical));
    }

    @Test
    void manifestRejectsMalformedUtf8AndOversize() throws Exception {
        Path manifest = temp.resolve(".atto.pkg.toml");
        Files.write(manifest, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
        Files.writeString(manifest, "x".repeat(64 * 1024 + 1));
        assertThrows(IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest, Map.of()));
    }

    @Test
    void canonicalManifestRejectsSymlinkedParent() throws Exception {
        Path real = temp.resolve("real");
        Files.createDirectories(real);
        Files.writeString(real.resolve(".atto.pkg.toml"), manifest("canonical"));
        Path linked = temp.resolve("linked");
        Files.createSymbolicLink(linked, real.getFileName());
        assertThrows(SecurityException.class,
                () -> OresProjectConfig.discover(linked.resolve("main.atto"), Map.of()));
    }

    @Test
    void canonicalManifestAdmitsRealLinkedAttoGuest() throws Exception {
        for (String name : java.util.List.of("hello.atto", "helper.atto")) {
            try (InputStream source =
                    AttoManifestHarnessReconciliationTest.class.getResourceAsStream(
                            "/atto/canonical-v1/" + name)) {
                assertTrue(source != null, "missing canonical guest resource: " + name);
                Files.copy(source, temp.resolve(name));
            }
        }
        Files.writeString(temp.resolve(".atto.pkg.toml"), manifest("canonical-guest"));
        // A legacy manifest must not redirect the canonical guest source graph.
        Files.writeString(temp.resolve(".oreslangc.cfg.toml"), manifest("legacy"));
        Path entry = temp.resolve("hello.atto");
        assertEquals("canonical-guest", OresProjectConfig.discover(entry, Map.of())
                .projectName().orElseThrow());
        var compiled = LinkedProgramRunner.validate(entry, Map.of());
        assertEquals(2, compiled.units().size());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var executed = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), stdout, stderr);
        assertEquals(2, executed.units().size());
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
        assertFalse(stderr.size() > 0, "unexpected AttoLang guest stderr");
    }
}
