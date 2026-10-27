package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class BoundedSourceReaderSnapshotTest {
    @TempDir Path temp;

    @Test
    void readsOneBoundedRegularSnapshot() throws Exception {
        Path source = temp.resolve("main.ores");
        Files.writeString(source, "pub fnc main(): int { return 42; }\n");

        BoundedSourceReader.ReadResult result = BoundedSourceReader.readWithByteCount(source);
        assertEquals("pub fnc main(): int { return 42; }\n", result.text());
        assertEquals(Files.size(source), result.byteCount());
    }

    @Test
    void supportedSymlinkAliasIsCanonicalizedBeforeNoFollowOpen() throws Exception {
        Path source = temp.resolve("real.ores");
        Files.writeString(source, "pub fnc value(): int { return 7; }\n");
        Path alias = temp.resolve("alias.ores");
        requireSymlink(alias, source);

        BoundedSourceReader.ReadResult result = BoundedSourceReader.readWithByteCount(alias);
        assertEquals("pub fnc value(): int { return 7; }\n", result.text());
        assertEquals(Files.size(source), result.byteCount());
    }

    @Test
    void danglingSymlinkNeverBecomesAnInputStreamTarget() throws Exception {
        Path alias = temp.resolve("dangling.ores");
        requireSymlink(alias, temp.resolve("missing.ores"));

        assertThrows(IOException.class, () -> BoundedSourceReader.readWithByteCount(alias));
    }

    @Test
    void directoryIsRejectedBeforeSnapshotRead() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("directory.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedSourceReader.readWithByteCount(directory));
    }

    @Test
    void unixSocketSpecialNodeIsRejectedBeforeOpen() throws Exception {
        Path socket = temp.resolve("source.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                server.bind(UnixDomainSocketAddress.of(socket));
            } catch (UnsupportedOperationException | IOException unsupported) {
                assumeTrue(false, "Unix-domain socket fixture unavailable: " + unsupported);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> BoundedSourceReader.readWithByteCount(socket));
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Unix-domain sockets unavailable: " + unsupported);
        }
    }

    @Test
    void oversizedRegularFileRejectsBeforeSnapshotAllocation() throws Exception {
        Path source = temp.resolve("oversized.ores");
        try (var channel = Files.newByteChannel(
                source,
                java.util.Set.of(
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE))) {
            channel.position(BoundedSourceReader.MAX_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(IllegalArgumentException.class,
                () -> BoundedSourceReader.readWithByteCount(source));
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
