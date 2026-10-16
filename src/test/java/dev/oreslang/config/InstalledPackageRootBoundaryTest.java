package dev.oreslang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class InstalledPackageRootBoundaryTest {
    @TempDir Path temp;

    @Test
    void installedLegacyProjectRootMustRemainInsidePackage() throws Exception {
        Path consumer = temp.resolve("consumer");
        Path dependency = consumer.resolve("zed_modules/acme/math");
        Path other = temp.resolve("different-project");
        Files.createDirectories(dependency.resolve("src"));
        Files.createDirectories(other);
        Files.writeString(consumer.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n[dependencies]\nmath = \"acme/math\"\n");
        Path imported = Files.writeString(dependency.resolve("src/helper.ores"),
                "pub fnc helper():int {return 42;}\n");
        Files.writeString(dependency.resolve(OresProjectConfig.MANIFEST_NAME),
                "schema_version = \"1\"\n[project]\nroot = \"generated\"\n");
        OresProjectConfig parent = OresProjectConfig.discover(
                consumer.resolve("main.ores"), Map.of());
        Path selectedRoot = dependency.resolve("generated");

        // A not-yet-generated project root beneath this package is permitted.
        assertEquals(selectedRoot.toAbsolutePath().normalize(),
                parent.forImportedPackage(imported, "pkg:math/src/helper").projectRoot());

        try {
            Files.createSymbolicLink(selectedRoot, other);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "filesystem symlinks unavailable");
        }
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> parent.forImportedPackage(imported, "pkg:math/src/helper"));
        assertTrue(foreign.getMessage().contains("escapes installed package"));

        Files.delete(selectedRoot);
        Files.createSymbolicLink(selectedRoot, temp.resolve("not-yet-present"));
        IllegalArgumentException dangling = assertThrows(IllegalArgumentException.class,
                () -> parent.forImportedPackage(imported, "pkg:math/src/helper"));
        assertTrue(dangling.getMessage().contains("dangling symlink"));
    }
}
