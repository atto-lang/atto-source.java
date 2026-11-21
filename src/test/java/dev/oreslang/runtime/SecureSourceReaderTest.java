package dev.oreslang.runtime;

import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class SecureSourceReaderTest {
    @TempDir Path root;

    @Test void readsNestedAttoBytesFromOpenFileDescriptor() throws Exception {
        Path dir = Files.createDirectories(root.resolve("src/pkg"));
        Path file = dir.resolve("entry.atto");
        String source = "pub fnc value() -> int { return 42; }";
        Files.writeString(file, source);
        // A manifest pins project.root above src/pkg so that the test
        // actually exercises secure directory traversal through two levels.
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [project]
                root = "."
                [source]
                roots = ["src"]
                """);
        boolean pinnedDirectories;
        try (var stream = Files.newDirectoryStream(root)) {
            pinnedDirectories = stream instanceof java.nio.file.SecureDirectoryStream<?>;
        }
        var read = SecureSourceReader.read(file,
                OresProjectConfig.discover(file, Map.of()), pinnedDirectories);
        assertEquals(source, read.text());
        assertEquals(source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                read.byteCount());
    }

    @Test void refusesMalformedUtf8() throws Exception {
        Path file = root.resolve("malformed.atto");
        Files.write(file, new byte[] {(byte) 0xc0, (byte) 0xaf});
        var project = OresProjectConfig.discover(file, Map.of());
        assertThrows(java.nio.charset.MalformedInputException.class,
                () -> SecureSourceReader.read(file, project));
    }

    @Test void preservesSourceByteLimit() throws Exception {
        Path file = root.resolve("oversized.atto");
        Files.write(file, new byte[BoundedSourceReader.MAX_BYTES + 1]);
        var project = OresProjectConfig.discover(file, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> SecureSourceReader.read(file, project));
    }

    @Test void rejectsIntermediateSymlinkEvenWhenItPointsInsideProject() throws Exception {
        Path real = Files.createDirectory(root.resolve("real"));
        Path alias = root.resolve("alias");
        Path source = real.resolve("value.atto");
        Files.writeString(source, "pub fnc value() -> int { return 7; }");
        try {
            Files.createSymbolicLink(alias, real.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported");
        }
        var project = OresProjectConfig.discover(source, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> SecureSourceReader.read(alias.resolve("value.atto"), project, true));
    }

    @Test void adversarialAdmissionRequiresOrUsesSecureDirectoryProvider() throws Exception {
        Path file = root.resolve("safe.atto");
        Files.writeString(file, "pub fnc value() -> int { return 42; }");
        var project = OresProjectConfig.discover(file, Map.of());
        try (var stream = Files.newDirectoryStream(root)) {
            if (stream instanceof java.nio.file.SecureDirectoryStream<?>) {
                assertTrue(SecureSourceReader.read(file, project, true).text().contains("42"));
            } else {
                assertThrows(SecurityException.class,
                        () -> SecureSourceReader.read(file, project, true));
            }
        }
    }

    @Test void rejectsBinaryNulBeforeParserCanInspectSource() throws Exception {
        Path file = root.resolve("binary.atto");
        Files.write(file, new byte[] {65, 0, 66});
        var project = OresProjectConfig.discover(file, Map.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecureSourceReader.read(file, project));
        assertTrue(ex.getMessage().contains("NUL"));
    }

    @Test void linkedAdversarialCompilerRequiresPinnedDirectories() throws Exception {
        Path file = root.resolve("guest.atto");
        Files.writeString(file, "pub fnc answer() -> int { return 42; }");
        try (var directory = Files.newDirectoryStream(root)) {
            if (directory instanceof java.nio.file.SecureDirectoryStream<?>) {
                assertDoesNotThrow(() -> LinkedProgramRunner.validate(
                        file, Map.of(), IsolatePolicy.untrustedActor(),
                        PermissionCheckMode.COMPILE));
            } else {
                assertThrows(SecurityException.class, () -> LinkedProgramRunner.validate(
                        file, Map.of(), IsolatePolicy.untrustedActor(),
                        PermissionCheckMode.COMPILE));
            }
        }
    }
}
