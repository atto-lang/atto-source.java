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
    void parentDirectorySymlinkSwapCannotRedirectGuestReadOrWrite()
            throws Exception {
        Path parent = Files.createDirectory(directory.resolve("authorized-parent"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path confidential = Files.writeString(outside.resolve("entry"), "secret");
        Path checked = parent.resolve("entry");
        Files.writeString(checked, "public");
        // Simulate a parent-directory replacement after path authorization.
        Path moved = directory.resolve("moved-original-parent");
        Files.move(parent, moved);
        try {
            Files.createSymbolicLink(parent, outside.getFileName());
        } catch (UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "symlink unavailable: " + unsupported);
        } catch (IOException unsupported) {
            assumeTrue(false, "symlink unavailable: " + unsupported);
        }
        assertThrows(IOException.class, () -> NativeIo.readUtf8NoFollow(checked),
                "parent path was swapped after authorization");
        assertThrows(IOException.class, () ->
                NativeIo.writeUtf8NoFollow(checked, "corrupted", false));
        assertThrows(IOException.class, () ->
                NativeIo.writeUtf8NoFollow(checked, "corrupted", true));
        assertThrows(IOException.class, () -> NativeIo.secureAtomicWrite(checked, "corrupted"));
        assertThrows(IOException.class, () -> NativeIo.secureRemove(checked));
        assertThrows(IOException.class, () -> NativeIo.secureListDirectory(parent));
        assertThrows(IOException.class, () -> NativeIo.secureFileAttributes(checked));
        assertEquals("secret", Files.readString(confidential));
        assertEquals("public", Files.readString(moved.resolve("entry")));
    }

    @Test
    void atomicReplaceListingMetadataAndRemovalUseOpenedParent() throws Exception {
        Path target = directory.resolve("atomic-content");
        NativeIo.secureAtomicWrite(target, "first");
        assertEquals("first", NativeIo.readUtf8NoFollow(target));
        NativeIo.secureAtomicWrite(target, "second");
        assertEquals("second", NativeIo.readUtf8NoFollow(target));
        assertTrue(NativeIo.secureFileAttributes(target).isRegularFile());
        assertTrue(NativeIo.secureListDirectory(directory).contains("atomic-content"));
        NativeIo.secureRemove(target);
        assertFalse(Files.exists(target));
    }

    @Test
    void guestReadAndWriteRequireSecureDirectoryProvider() throws Exception {
        Path root = directory.toAbsolutePath().getRoot();
        try (var stream = Files.newDirectoryStream(root)) {
            if (!(stream instanceof java.nio.file.SecureDirectoryStream<?>)) {
                Path target = directory.resolve("plain");
                Files.writeString(target, "content");
                assertThrows(IOException.class, () -> NativeIo.readUtf8NoFollow(target));
                assertThrows(IOException.class,
                        () -> NativeIo.writeUtf8NoFollow(target, "change", false));
                assertEquals("content", Files.readString(target));
            }
        }
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
