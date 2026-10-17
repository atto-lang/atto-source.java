package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Direct host file-open regressions (not permission-layer-only mocks).
 * The caller already authorized a canonical path; the final directory entry
 * can still be swapped to a symlink before opening it. Never follow that link.
 */
final class NativeIoSymlinkBoundaryTest {
    @TempDir Path directory;

    private Path symlink(Path target) throws IOException {
        Path link = directory.resolve("swapped-leaf");
        try {
            return Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "symbolic links unavailable for this filesystem: " + unsupported);
            throw new AssertionError(unsupported);
        } catch (IOException denied) {
            assumeTrue(false, "symbolic link creation unavailable: " + denied);
            throw denied;
        }
    }

    @Test
    void openedReadDoesNotFollowSwappedFinalSymlink() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret"), "confidential");
        Path link = symlink(secret);

        assertThrows(IOException.class, () -> NativeIo.readUtf8NoFollow(link));
        assertEquals("confidential", NativeIo.readUtf8NoFollow(secret));
    }

    @Test
    void writeAndAppendDoNotFollowSwappedFinalSymlink() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret"), "protected");
        Path link = symlink(secret);

        assertThrows(IOException.class,
                () -> NativeIo.writeUtf8NoFollow(link, "overwrite", false));
        assertThrows(IOException.class,
                () -> NativeIo.writeUtf8NoFollow(link, "append", true));
        assertEquals("protected", Files.readString(secret),
                "neither denied open may change the symlink target");
    }

    @Test
    void ordinaryCreationAndUtf8AppendRemainUsable() throws Exception {
        Path fresh = directory.resolve("new-file");
        NativeIo.writeUtf8NoFollow(fresh, "hé", false);
        NativeIo.writeUtf8NoFollow(fresh, "llo 🦉", true);
        assertEquals("héllo 🦉", NativeIo.readUtf8NoFollow(fresh));
    }

    @Test
    void guestReadTextRejectsOversizeFileWithoutStreamingIntoUnboundedHeap()
            throws Exception {
        Path large = directory.resolve("oversize-read-text");
        // A sparse temp file avoids allocating an entire 16 MiB fixture
        // while exercising the exact host I/O boundary and size guard.
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) {
            file.setLength((long) BoundedSourceReader.MAX_BYTES + 1);
        }
        IOException denied = assertThrows(IOException.class,
                () -> NativeIo.readUtf8NoFollow(large));
        assertTrue(denied.getMessage().contains("16 MiB"),
                "fail closed with an explicit host-side payload limit");
    }

    @Test
    void strictUtf8DecodingDoesNotTurnMalformedBytesIntoReplacementCharacters()
            throws Exception {
        Path invalid = directory.resolve("invalid-utf8");
        Files.write(invalid, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> NativeIo.readUtf8NoFollow(invalid));
    }
}
