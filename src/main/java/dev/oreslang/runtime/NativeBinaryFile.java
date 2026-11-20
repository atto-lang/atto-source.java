package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guest binary file handle (spec chapter 26, Async file I/O v1).
 *
 * <p>An append-oriented, positionally readable file for logs and journals.
 * Every method blocks and therefore runs ONLY on the native I/O worker pool
 * through {@link AsyncNativeIo#submit}; guest code sees Futures and never
 * parks a scheduler thread. The handle is affine (one owning task), so
 * methods are serialized per handle; the monitor keeps a misbehaving host
 * caller from interleaving appends.</p>
 */
public final class NativeBinaryFile {
    /** Largest single read or append, matching the Bytes bound. */
    public static final int MAX_IO_BYTES = OresBytes.MAX_LENGTH;

    private final Path path;
    private final FileChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeBinaryFile(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    public Path path() { return path; }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("closed BinaryFile " + path);
    }

    /** Append all of data at the end of the file; returns its start offset. */
    public synchronized long append(OresBytes data) throws IOException {
        requireOpen();
        byte[] bytes = data.toArrayCopy();
        long offset = channel.size();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long at = offset;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
        return offset;
    }

    /** Flush file content to stable storage (data, not necessarily metadata). */
    public synchronized void sync() throws IOException {
        requireOpen();
        channel.force(false);
    }

    /** Read up to length bytes at offset; fewer at end of file, empty past it. */
    public synchronized OresBytes readAt(long offset, int length) throws IOException {
        requireOpen();
        if (offset < 0) throw new IllegalArgumentException("BinaryFile.read_at_async offset must be >= 0");
        if (length < 0 || length > MAX_IO_BYTES) {
            throw new IllegalArgumentException(
                    "BinaryFile.read_at_async length must be 0.." + MAX_IO_BYTES);
        }
        long size = channel.size();
        if (offset >= size || length == 0) return OresBytes.empty();
        int want = (int) Math.min(length, size - offset);
        ByteBuffer buffer = ByteBuffer.allocate(want);
        long at = offset;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, at);
            if (n < 0) break;
            at += n;
        }
        byte[] out = new byte[buffer.position()];
        buffer.flip();
        buffer.get(out);
        return OresBytes.fromDetached(out);
    }

    public synchronized long size() throws IOException {
        requireOpen();
        return channel.size();
    }

    /** Shrink the file to size bytes (e.g. drop a torn tail during recovery). */
    public synchronized void truncate(long size) throws IOException {
        requireOpen();
        if (size < 0 || size > channel.size()) {
            throw new IllegalArgumentException(
                    "BinaryFile.truncate_async size must be 0..current size");
        }
        channel.truncate(size);
    }

    public synchronized void close() throws IOException {
        if (closed.compareAndSet(false, true)) channel.close();
    }
}
