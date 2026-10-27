package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class BoundedSourceReaderNoFollowTest {
    @TempDir Path temp;

    @Test
    void callerAliasCanonicalizesOnceBeforePhysicalSnapshot() throws Exception {
        Path target = temp.resolve("target.ores");
        Files.writeString(target, "pub fnc answer(): int { return 42; }\n");
        Path alias = temp.resolve("alias.ores");
        requireSymlink(alias, target);

        BoundedSourceReader.ReadResult result = BoundedSourceReader.readWithByteCount(alias);
        assertEquals("pub fnc answer(): int { return 42; }\n", result.text());
        assertEquals(Files.size(target), result.byteCount());
    }

    @Test
    void physicalSnapshotRejectsSymlinkLeaf() throws Exception {
        Path target = temp.resolve("leaf-target.ores");
        Files.writeString(target, "pub fnc answer(): int { return 42; }\n");
        Path alias = temp.resolve("leaf-alias.ores");
        requireSymlink(alias, target);

        assertRejectedPhysical(alias);
    }

    @Test
    void physicalSnapshotRejectsSymlinkParent() throws Exception {
        Path realDirectory = temp.resolve("real-dir");
        Files.createDirectories(realDirectory);
        Path target = realDirectory.resolve("main.ores");
        Files.writeString(target, "pub fnc main(): void { return; }\n");
        Path aliasDirectory = temp.resolve("alias-dir");
        requireSymlink(aliasDirectory, realDirectory);

        assertRejectedPhysical(aliasDirectory.resolve("main.ores"));
    }

    @Test
    void physicalSnapshotAcceptsCanonicalRegularFile() throws Exception {
        Path target = temp.resolve("canonical.ores");
        Files.writeString(target, "pub fnc main(): void { return; }\n");
        Path physical = target.toRealPath();

        BoundedSourceReader.ReadResult result =
                BoundedSourceReader.readPhysicalWithByteCount(physical);
        assertEquals(Files.readString(target), result.text());
        assertEquals(Files.size(target), result.byteCount());
    }


    @Test
    void callerDanglingAliasFailsBeforePhysicalSnapshot() throws Exception {
        Path dangling = temp.resolve("dangling.ores");
        requireSymlink(dangling, temp.resolve("missing.ores"));
        assertThrows(IOException.class,
                () -> BoundedSourceReader.readWithByteCount(dangling));
    }

    @Test
    void physicalSnapshotRejectsDirectory() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("directory.ores"));
        assertRejectedPhysical(directory);
    }

    @Test
    void physicalSnapshotRejectsUnixSocketSpecialNodeBeforeOpen() throws Exception {
        Path socket = temp.resolve("source.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                server.bind(UnixDomainSocketAddress.of(socket));
            } catch (UnsupportedOperationException | IOException unavailable) {
                assumeTrue(false, "Unix-domain socket fixture unavailable: " + unavailable);
            }
            assertRejectedPhysical(socket);
        } catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "Unix-domain sockets unavailable: " + unavailable);
        }
    }

    @Test
    void physicalSnapshotRejectsOversizedRegularFileBeforeAllocation() throws Exception {
        Path oversized = temp.resolve("oversized.ores");
        try (var channel = Files.newByteChannel(
                oversized,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            channel.position(BoundedSourceReader.MAX_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(IllegalArgumentException.class,
                () -> BoundedSourceReader.readPhysicalWithByteCount(oversized.toRealPath()));
    }

    private static void assertRejectedPhysical(Path path) {
        try {
            BoundedSourceReader.readPhysicalWithByteCount(path);
            fail("physical source snapshot unexpectedly followed symlink path: " + path);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("symbolic-link")
                            || expected.getMessage().contains("regular no-follow"),
                    expected.getMessage());
        } catch (IOException expected) {
            // SecureDirectoryStream providers commonly reject a no-follow
            // symlink traversal with a provider-specific IOException.
        }
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
