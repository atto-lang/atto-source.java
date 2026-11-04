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
        var read = SecureSourceReader.read(file, OresProjectConfig.discover(file, Map.of()));
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
}
