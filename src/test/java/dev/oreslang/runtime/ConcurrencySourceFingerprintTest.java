package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ConcurrencySourceFingerprintTest {
    @TempDir Path root;

    @Test
    void deterministicallyHashesEntryAndImportedWorkspaceSources() throws Exception {
        Path entry = root.resolve("main.ores");
        Path imported = root.resolve("helpers.ores");
        Files.writeString(entry, "import helpers;\n");
        Files.writeString(imported, "pub fnc answer() -> int { return 1; }\n");
        String original = ConcurrencySourceFingerprint.ofWorkspace(root);
        assertEquals(64, original.length());
        assertEquals(original, ConcurrencySourceFingerprint.ofWorkspace(root));
        Files.writeString(imported, "pub fnc answer() -> int { return 2; }\n");
        assertNotEquals(original, ConcurrencySourceFingerprint.ofWorkspace(root));
    }

    @Test
    void addingOrRenamingASourceChangesItsFingerprint() throws Exception {
        Files.writeString(root.resolve("main.ores"), "fnc main() {}\n");
        String original = ConcurrencySourceFingerprint.ofWorkspace(root);
        Path second = root.resolve("secondary.ores");
        Files.writeString(second, "const int x = 1;\n");
        String withSecond = ConcurrencySourceFingerprint.ofWorkspace(root);
        assertNotEquals(original, withSecond);
        Files.move(second, root.resolve("renamed.ores"));
        assertNotEquals(withSecond, ConcurrencySourceFingerprint.ofWorkspace(root));
    }

    @Test
    void rejectsSymlinkSourceInsteadOfHashingPossiblyExternalImport() throws Exception {
        Path external = root.resolve("real.txt");
        Files.writeString(external, "external source bytes");
        try {
            Files.createSymbolicLink(root.resolve("unsafe.ores"), external.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return; // Not all test filesystems support symlinks.
        }
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencySourceFingerprint.ofWorkspace(root));
    }

    @Test
    void rejectsSymlinkedDirectoryWithoutSourceSuffix() throws Exception {
        Path module = root.resolve("real-module");
        Files.createDirectory(module);
        Files.writeString(module.resolve("library.ores"), "fnc value() { return 1; }\n");
        Files.writeString(root.resolve("main.ores"), "import library;\n");
        try {
            Files.createSymbolicLink(root.resolve("linked-module"), module.getFileName());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return;
        }
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencySourceFingerprint.ofWorkspace(root));
    }

    @Test
    void excludedGeneratedOutputCannotInvalidateSourceSnapshot() throws Exception {
        Files.writeString(root.resolve("main.ores"), "fnc main() {}\n");
        Path generated = Files.createDirectory(root.resolve("target"));
        Files.writeString(generated.resolve("Main.java"), "class Main {}");
        String baseline = ConcurrencySourceFingerprint.ofWorkspace(root);
        Files.writeString(generated.resolve("Main.java"), "class Main { int x; }");
        assertEquals(baseline, ConcurrencySourceFingerprint.ofWorkspace(root));
    }

    @Test
    void rejectsEmptyWorkspaceWithoutSourceDeclarations() {
        assertThrows(IllegalArgumentException.class,
                () -> ConcurrencySourceFingerprint.ofWorkspace(root));
    }
}
