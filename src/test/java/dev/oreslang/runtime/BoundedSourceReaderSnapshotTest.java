package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class BoundedSourceReaderSnapshotTest {
    @TempDir Path temp;

    @Test
    void regularFileReadsAsOneBoundedSnapshot() throws Exception {
        Path source = temp.resolve("main.ores");
        Files.writeString(source, "pub fnc main(): int { return 42; }\n");

        BoundedSourceReader.ReadResult result = BoundedSourceReader.readWithByteCount(source);
        assertEquals("pub fnc main(): int { return 42; }\n", result.text());
        assertEquals(Files.size(source), result.byteCount());
    }

    @Test
    void symlinkAliasCanonicalizesBeforeDescriptorRead() throws Exception {
        Path target = temp.resolve("target.ores");
        Files.writeString(target, "pub fnc target(): int { return 42; }\n");
        Path alias = temp.resolve("alias.ores");
        requireSymlink(alias, target);

        BoundedSourceReader.ReadResult result = BoundedSourceReader.readWithByteCount(alias);
        assertEquals("pub fnc target(): int { return 42; }\n", result.text());
    }

    @Test
    void danglingSymlinkRejectsBeforeRead() throws Exception {
        Path alias = temp.resolve("dangling.ores");
        requireSymlink(alias, temp.resolve("missing.ores"));

        assertThrows(IOException.class, () -> BoundedSourceReader.readWithByteCount(alias));
    }

    @Test
    void directoryIsRejectedAsSourceBeforeDescriptorRead() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedSourceReader.readWithByteCount(temp));
    }

    @Test
    void descriptorPassDetectsSameSizeMutation() {
        byte[] first = "abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "wxyz".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MutatingChannel channel = new MutatingChannel(first, second);

        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedSourceReader.snapshotChannel(channel));
    }

    @Test
    void descriptorPassAcceptsStableBytes() throws Exception {
        byte[] bytes = "stable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StableChannel channel = new StableChannel(bytes);

        assertArrayEquals(bytes, BoundedSourceReader.snapshotChannel(channel));
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

    private static class StableChannel implements SeekableByteChannel {
        protected final byte[] bytes;
        protected int position;
        protected boolean open = true;

        StableChannel(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override public int read(ByteBuffer dst) {
            if (position >= bytes.length) return -1;
            int count = Math.min(dst.remaining(), bytes.length - position);
            dst.put(bytes, position, count);
            position += count;
            return count;
        }

        @Override public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override public long position() {
            return position;
        }

        @Override public SeekableByteChannel position(long newPosition) {
            position = Math.toIntExact(newPosition);
            return this;
        }

        @Override public long size() {
            return bytes.length;
        }

        @Override public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isOpen() {
            return open;
        }

        @Override public void close() {
            open = false;
        }
    }

    private static final class MutatingChannel extends StableChannel {
        private final byte[] second;
        private boolean secondPass;

        MutatingChannel(byte[] first, byte[] second) {
            super(first);
            this.second = second.clone();
            if (first.length != second.length) throw new IllegalArgumentException();
        }

        @Override public SeekableByteChannel position(long newPosition) {
            super.position(newPosition);
            if (newPosition == 0 && position != bytes.length) {
                secondPass = true;
            }
            return this;
        }

        @Override public int read(ByteBuffer dst) {
            byte[] current = secondPass ? second : bytes;
            if (position >= current.length) return -1;
            int count = Math.min(dst.remaining(), current.length - position);
            dst.put(current, position, count);
            position += count;
            return count;
        }
    }
}
